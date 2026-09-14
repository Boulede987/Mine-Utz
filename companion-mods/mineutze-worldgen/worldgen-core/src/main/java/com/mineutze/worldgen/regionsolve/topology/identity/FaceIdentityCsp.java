package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.EdgeDisplacement;

import com.mineutze.worldgen.regionsolve.topology.Salt;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Decides each free face's {@link BiomeId} ONLY - zero width/size concern, see {@link
 * TriangleIdentitySizeAssignment} (only caller) for how identity later gets a width from
 * {@link FaceWidthPicker}.
 *
 * <p>MRV (most-constrained-face-first) + maintained arc consistency (AC-3-style binary
 * propagation, {@link #forwardCheck}, PLUS ternary consistency, {@link #reviseTriple},
 * over every real mesh-vertex triple — {@link #mutualNeighborTrianglesByFace} —
 * interleaved to one joint fixed point) during backtracking. See DECISIONS.md 2026-09-01
 * for why plain forward checking and binary-only AC-3 both proved insufficient at scale.
 *
 * <p>{@link #backtrack} also enforces a hard node budget ({@link SearchBudget}) - safety
 * net independent of propagation quality. Some instance can be genuinely hard; "hangs
 * indefinitely" is worse than "fails loud with a diagnosis" on a live game path. Not a
 * primary fix, propagation above is.
 *
 * <p>Candidate biomes tried in per-{@code (worldSeed, faceIndex)} DETERMINISTIC JITTERED
 * order, never fixed, AND fairness-biased (see {@link FaceFairnessQuota}): legal
 * candidates ranked by current deficit descending, so an under-represented biome is
 * preferred over an already-quota-filled one when both equally legal; seeded jitter still
 * breaks ties, keeps result deterministic. Never overrides legality/backtracking, only
 * reorders already-equally-legal choices. See DECISIONS.md 2026-08-23 "Candidate biomes
 * tried in deterministic jittered order."
 */
final class FaceIdentityCsp {

    private static final long CANDIDATE_ORDER_SALT = Salt.of("TriangleIdentitySizeAssignment.candidateOrder");

    /** Classic hash-spreading multiplier (same convention as {@code java.util.Objects.hash}, and {@code EdgeDisplacement}/{@code WeightedVertexPlacement}'s {@code HASH_MULTIPLIER}). */
    private static final long HASH_MULTIPLIER = 31;

    private FaceIdentityCsp() {
    }

    /**
     * Runs the full backtracking search to completion.
     *
     * @return fully-assigned identity map, or empty if no legal assignment exists for the
     * given domains/adjacency (see {@code BiomeGraph.requireEveryLocalNeighborhoodSolvable}:
     * only rules out "impossible in principle" configs, not every real local arrangement).
     */
    static Optional<Map<Integer, BiomeId>> solve(
        Map<Integer, List<BiomeId>> domains, FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices,
        BiomeGraph graph, long worldSeed, FaceFairnessQuota fairness
    ) {
        Map<Integer, BiomeId> assigned = new LinkedHashMap<>();
        SearchBudget budget = SearchBudget.forFaceCount(freeFaceIndices.size());
        Map<Integer, List<int[]>> mutualNeighborTrianglesByFace = mutualNeighborTrianglesByFace(adjacency, freeFaceIndices);
        if (!backtrack(domains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness, budget, mutualNeighborTrianglesByFace)) {
            return Optional.empty();
        }
        return Optional.of(assigned);
    }

    static boolean legalAgainstFixedNeighbors(int faceIndex, BiomeId candidate, FaceAdjacency.Adjacency adjacency, Map<Integer, BiomeId> fixedFaceBiomes, BiomeGraph graph) {
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            BiomeId fixedNeighbor = fixedFaceBiomes.get(neighbor);
            if (fixedNeighbor != null && !graph.isAllowed(candidate, fixedNeighbor)) {
                return false;
            }
        }
        return respectsSelfAdjacencyLimit(faceIndex, candidate, adjacency, fixedFaceBiomes::get, graph);
    }

    private static boolean backtrack(
        Map<Integer, List<BiomeId>> domains, Map<Integer, BiomeId> assigned, FaceAdjacency.Adjacency adjacency,
        Set<Integer> freeFaceIndices, BiomeGraph graph, long worldSeed, FaceFairnessQuota fairness, SearchBudget budget,
        Map<Integer, List<int[]>> mutualNeighborTrianglesByFace
    ) {
        budget.visitNode(freeFaceIndices.size());
        Optional<Integer> nextFace = mostConstrainedUnassignedFace(domains, assigned, freeFaceIndices);
        if (nextFace.isEmpty()) {
            return true; // every free face assigned
        }
        int faceIndex = nextFace.get();

        for (BiomeId candidate : candidatesInFairnessOrder(domains.get(faceIndex), worldSeed, faceIndex, fairness)) {
            if (tryAssignCandidate(
                faceIndex, candidate, domains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness, budget, mutualNeighborTrianglesByFace
            )) {
                return true;
            }
        }
        return false;
    }

    /** Assigns {@code candidate} to {@code faceIndex}, propagates, and recurses - undoing the assignment (and its fairness bump) again if that doesn't lead to a solution. @return whether this candidate led to a full solution. */
    private static boolean tryAssignCandidate(
        int faceIndex, BiomeId candidate, Map<Integer, List<BiomeId>> domains, Map<Integer, BiomeId> assigned,
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, BiomeGraph graph, long worldSeed,
        FaceFairnessQuota fairness, SearchBudget budget, Map<Integer, List<int[]>> mutualNeighborTrianglesByFace
    ) {
        if (!legalAgainstAssignedNeighbors(faceIndex, candidate, adjacency, assigned, graph)) {
            return false;
        }

        assigned.put(faceIndex, candidate);
        fairness.increment(candidate);
        Map<Integer, List<BiomeId>> prunedDomains = forwardCheck(
            faceIndex, candidate, domains, adjacency, freeFaceIndices, assigned, graph, mutualNeighborTrianglesByFace
        );
        boolean solved = prunedDomains != null
            && backtrack(prunedDomains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness, budget, mutualNeighborTrianglesByFace);
        if (!solved) {
            fairness.decrement(candidate);
            assigned.remove(faceIndex);
        }
        return solved;
    }

    /**
     * AC-3-style arc-consistency propagation (binary: {@link #reviseAgainstSingleValue}/
     * {@link #reviseAgainstDomain}) PLUS mesh-vertex triple-consistency (ternary: {@link
     * #reviseTriple}) run to a joint fixed point — see class doc for why binary alone isn't
     * enough. Binary arc consistency only asks "does face X have SOME compatible value in
     * face Y's domain" one pair at a time; can leave a domain looking fine against every
     * neighbor INDIVIDUALLY while no value works against ALL of a real mesh-vertex triple
     * SIMULTANEOUSLY - gap measured on {@code worldSeed=2060} at {@code HEX_SIZE_BLOCKS=325}:
     * 3 mutually-adjacent faces (real hex vertex, guaranteed by {@code HexMeshBuilder}'s
     * vertex-degree-3 property) each kept candidates that together formed no legal triple
     * (shared 4-value domain's only two legal pairs were disjoint - no 3-clique). A shrink
     * from either kind can enable further shrinks of the OTHER kind, so both worklists drain
     * together in one loop until NEITHER changes anything, not run once each.
     *
     * @return copy of {@code domains} with every reachable domain pruned to values
     * consistent with every neighbor's domain (binary) AND every real mesh-vertex triple it
     * participates in (ternary), or {@code null} if propagation empties any domain (dead
     * end, detected as early as this consistency level can - still not a completeness
     * guarantee, see {@link SearchBudget}).
     */
    private static Map<Integer, List<BiomeId>> forwardCheck(
        int faceIndex, BiomeId candidate, Map<Integer, List<BiomeId>> domains, FaceAdjacency.Adjacency adjacency,
        Set<Integer> freeFaceIndices, Map<Integer, BiomeId> assigned, BiomeGraph graph,
        Map<Integer, List<int[]>> mutualNeighborTrianglesByFace
    ) {
        Map<Integer, List<BiomeId>> next = new HashMap<>(domains);
        Deque<Integer> binaryWorklist = new ArrayDeque<>();
        Deque<int[]> triangleWorklist = new ArrayDeque<>();

        if (!reviseDirectNeighborsAgainstNewAssignment(faceIndex, candidate, next, adjacency, freeFaceIndices, assigned, graph, binaryWorklist)) {
            return null;
        }
        enqueueTrianglesOf(faceIndex, mutualNeighborTrianglesByFace, assigned, triangleWorklist);

        while (!binaryWorklist.isEmpty() || !triangleWorklist.isEmpty()) {
            if (!drainBinaryWorklist(binaryWorklist, triangleWorklist, next, adjacency, freeFaceIndices, assigned, graph, mutualNeighborTrianglesByFace)) {
                return null;
            }
            if (!triangleWorklist.isEmpty()
                && !reviseNextTriple(triangleWorklist, binaryWorklist, next, graph, mutualNeighborTrianglesByFace, assigned)) {
                return null;
            }
        }
        return next;
    }

    /** Prunes every direct, still-undecided neighbor of {@code faceIndex} against its brand-new {@code candidate} value - the seed step for the propagation loop below. @return false if any of those neighbors' domains empties. */
    private static boolean reviseDirectNeighborsAgainstNewAssignment(
        int faceIndex, BiomeId candidate, Map<Integer, List<BiomeId>> next, FaceAdjacency.Adjacency adjacency,
        Set<Integer> freeFaceIndices, Map<Integer, BiomeId> assigned, BiomeGraph graph, Deque<Integer> binaryWorklist
    ) {
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            if (!freeFaceIndices.contains(neighbor) || assigned.containsKey(neighbor)) {
                continue;
            }
            if (!reviseAgainstSingleValue(neighbor, candidate, next, graph, binaryWorklist)) {
                return false;
            }
        }
        return true;
    }

    /** Drains {@code binaryWorklist} to empty, propagating each shrunk domain to its own undecided neighbors (and enqueuing any mesh-vertex triples it participates in). @return false if propagation empties any domain along the way. */
    private static boolean drainBinaryWorklist(
        Deque<Integer> binaryWorklist, Deque<int[]> triangleWorklist, Map<Integer, List<BiomeId>> next,
        FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, Map<Integer, BiomeId> assigned, BiomeGraph graph,
        Map<Integer, List<int[]>> mutualNeighborTrianglesByFace
    ) {
        while (!binaryWorklist.isEmpty()) {
            int shrunk = binaryWorklist.poll();
            List<BiomeId> shrunkDomain = next.get(shrunk);
            for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(shrunk, Set.of())) {
                if (!freeFaceIndices.contains(neighbor) || assigned.containsKey(neighbor)) {
                    continue;
                }
                if (!reviseAgainstDomain(neighbor, shrunkDomain, next, graph, binaryWorklist)) {
                    return false;
                }
            }
            enqueueTrianglesOf(shrunk, mutualNeighborTrianglesByFace, assigned, triangleWorklist);
        }
        return true;
    }

    /** Pops and revises one mesh-vertex triple, re-queuing any face it shrinks (for both further binary propagation and its own other triples). @return false if the triple's own revision empties a domain. */
    private static boolean reviseNextTriple(
        Deque<int[]> triangleWorklist, Deque<Integer> binaryWorklist, Map<Integer, List<BiomeId>> next, BiomeGraph graph,
        Map<Integer, List<int[]>> mutualNeighborTrianglesByFace, Map<Integer, BiomeId> assigned
    ) {
        int[] triangle = triangleWorklist.poll();
        Set<Integer> shrunkByTriple = reviseTriple(triangle, next, graph);
        if (shrunkByTriple == null) {
            return false;
        }
        for (int shrunkFace : shrunkByTriple) {
            binaryWorklist.add(shrunkFace);
            enqueueTrianglesOf(shrunkFace, mutualNeighborTrianglesByFace, assigned, triangleWorklist);
        }
        return true;
    }

    private static void enqueueTrianglesOf(
        int faceIndex, Map<Integer, List<int[]>> mutualNeighborTrianglesByFace, Map<Integer, BiomeId> assigned, Deque<int[]> triangleWorklist
    ) {
        for (int[] triangle : mutualNeighborTrianglesByFace.getOrDefault(faceIndex, List.of())) {
            if (assigned.containsKey(triangle[0]) || assigned.containsKey(triangle[1]) || assigned.containsKey(triangle[2])) {
                continue; // a fixed member reduces this to ordinary pairwise consistency, already handled above
            }
            triangleWorklist.add(triangle);
        }
    }

    /** Prunes {@code domains.get(faceToRevise)} to values legal against the single value {@code fixedValue} - enqueues {@code faceToRevise} if this actually shrinks it. @return false if the domain empties (an immediate dead end). */
    private static boolean reviseAgainstSingleValue(
        int faceToRevise, BiomeId fixedValue, Map<Integer, List<BiomeId>> domains, BiomeGraph graph, Deque<Integer> worklist
    ) {
        List<BiomeId> before = domains.get(faceToRevise);
        List<BiomeId> pruned = before.stream().filter(candidate -> graph.isAllowed(fixedValue, candidate)).toList();
        if (pruned.isEmpty()) {
            return false;
        }
        if (pruned.size() != before.size()) {
            domains.put(faceToRevise, pruned);
            worklist.add(faceToRevise);
        }
        return true;
    }

    /** Prunes {@code domains.get(faceToRevise)} to values with at least one legal counterpart still in {@code otherDomain} - enqueues {@code faceToRevise} if this actually shrinks it. @return false if the domain empties (an immediate dead end). */
    private static boolean reviseAgainstDomain(
        int faceToRevise, List<BiomeId> otherDomain, Map<Integer, List<BiomeId>> domains, BiomeGraph graph, Deque<Integer> worklist
    ) {
        List<BiomeId> before = domains.get(faceToRevise);
        List<BiomeId> revised = before.stream()
            .filter(candidate -> otherDomain.stream().anyMatch(other -> graph.isAllowed(candidate, other)))
            .toList();
        if (revised.isEmpty()) {
            return false;
        }
        if (revised.size() != before.size()) {
            domains.put(faceToRevise, revised);
            worklist.add(faceToRevise);
        }
        return true;
    }

    /**
     * Ternary consistency for one real mesh-vertex triple {@code (faceA, faceB, faceC)}
     * (all mutually adjacent - see {@link #mutualNeighborTrianglesByFace}): prunes each
     * face's domain to values with SOME simultaneous, mutually-legal completion in the
     * OTHER TWO faces' domains - a 3-way check binary arc consistency can't express, since
     * it only reasons about one pair at a time. The exact gap that let {@code
     * worldSeed=2060}'s dead end (see class doc) go undetected until brute chronological
     * backtracking rediscovered it.
     *
     * @return set of faces (among {@code faceA, faceB, faceC}) whose domain shrank, or
     * {@code null} if any domain emptied (dead end - no legal joint assignment exists for
     * this triple given the other two's current domains).
     */
    private static Set<Integer> reviseTriple(int[] triangle, Map<Integer, List<BiomeId>> domains, BiomeGraph graph) {
        int faceA = triangle[0];
        int faceB = triangle[1];
        int faceC = triangle[2];
        List<BiomeId> domainA = domains.get(faceA);
        List<BiomeId> domainB = domains.get(faceB);
        List<BiomeId> domainC = domains.get(faceC);

        List<BiomeId> revisedA = domainA.stream().filter(value -> hasLegalCompletion(value, domainB, domainC, graph)).toList();
        List<BiomeId> revisedB = domainB.stream().filter(value -> hasLegalCompletion(value, domainA, domainC, graph)).toList();
        List<BiomeId> revisedC = domainC.stream().filter(value -> hasLegalCompletion(value, domainA, domainB, graph)).toList();
        if (revisedA.isEmpty() || revisedB.isEmpty() || revisedC.isEmpty()) {
            return null;
        }

        Set<Integer> shrunk = new java.util.HashSet<>();
        if (revisedA.size() != domainA.size()) {
            domains.put(faceA, revisedA);
            shrunk.add(faceA);
        }
        if (revisedB.size() != domainB.size()) {
            domains.put(faceB, revisedB);
            shrunk.add(faceB);
        }
        if (revisedC.size() != domainC.size()) {
            domains.put(faceC, revisedC);
            shrunk.add(faceC);
        }
        return shrunk;
    }

    /** Whether some pair (one value from each of {@code domainX}, {@code domainY}) is mutually legal AND both legal against {@code value} - the actual joint 3-way check {@link #reviseTriple} needs. */
    private static boolean hasLegalCompletion(BiomeId value, List<BiomeId> domainX, List<BiomeId> domainY, BiomeGraph graph) {
        for (BiomeId candidateFromX : domainX) {
            if (!graph.isAllowed(value, candidateFromX)) {
                continue;
            }
            for (BiomeId candidateFromY : domainY) {
                if (graph.isAllowed(value, candidateFromY) && graph.isAllowed(candidateFromX, candidateFromY)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Every real mesh-vertex triple among free faces ({@link
     * FaceAdjacency.Adjacency#mutuallyAdjacentFaceTriples}), indexed by member face for
     * cheap "which triangles does this face's shrink affect" lookups. Computed once per
     * {@link #solve} call (adjacency never changes during backtracking, only domains do),
     * not per search node.
     */
    private static Map<Integer, List<int[]>> mutualNeighborTrianglesByFace(FaceAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices) {
        Map<Integer, List<int[]>> byFace = new HashMap<>();
        for (int[] triangle : adjacency.mutuallyAdjacentFaceTriples(freeFaceIndices)) {
            for (int face : triangle) {
                byFace.computeIfAbsent(face, key -> new ArrayList<>()).add(triangle);
            }
        }
        return byFace;
    }

    private static Optional<Integer> mostConstrainedUnassignedFace(Map<Integer, List<BiomeId>> domains, Map<Integer, BiomeId> assigned, Set<Integer> freeFaceIndices) {
        return freeFaceIndices.stream()
            .filter(faceIndex -> !assigned.containsKey(faceIndex))
            .min(Comparator.<Integer>comparingInt(faceIndex -> domains.get(faceIndex).size()).thenComparingInt(faceIndex -> faceIndex));
    }

    private static boolean legalAgainstAssignedNeighbors(int faceIndex, BiomeId candidate, FaceAdjacency.Adjacency adjacency, Map<Integer, BiomeId> assigned, BiomeGraph graph) {
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            BiomeId assignedNeighbor = assigned.get(neighbor);
            if (assignedNeighbor != null && !graph.isAllowed(candidate, assignedNeighbor)) {
                return false;
            }
        }
        return respectsSelfAdjacencyLimit(faceIndex, candidate, adjacency, assigned::get, graph);
    }

    /**
     * Whether assigning {@code faceIndex = candidate} respects {@code candidate}'s {@link
     * com.mineutze.worldgen.biomegraph.BiomeNode#selfAdjacencyLimit} — no-op (always true)
     * unless a biome opts OUT with an explicit low limit (today's default is uncapped).
     * Checks BOTH sides of every same-candidate neighbor
     * pair: {@code faceIndex}'s resulting same-biome-neighbor count must stay within the
     * limit, AND for each already-decided neighbor N also {@code candidate}, adding {@code
     * faceIndex} as one more same-biome neighbor of N must not push N over ITS OWN limit —
     * N's legality was only checked against what existed at N's decision time, so a later
     * same-biome neighbor must be re-verified against N's cap too, not just faceIndex's.
     *
     * @param neighborBiomeOf reads a neighbor's currently-decided biome (assigned or
     *                        fixed), or {@code null} if undecided - lets one method serve
     *                        both {@link #legalAgainstFixedNeighbors} and {@link
     *                        #legalAgainstAssignedNeighbors} without duplicating logic.
     */
    private static boolean respectsSelfAdjacencyLimit(
        int faceIndex, BiomeId candidate, FaceAdjacency.Adjacency adjacency,
        java.util.function.IntFunction<BiomeId> neighborBiomeOf, BiomeGraph graph
    ) {
        int limit = graph.node(candidate).selfAdjacencyLimit();
        if (limit <= 0) {
            return true;
        }
        int ownCount = countNeighborsWithBiome(faceIndex, candidate, adjacency, neighborBiomeOf);
        if (ownCount > limit) {
            return false;
        }
        return adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of()).stream()
            .allMatch(neighbor -> neighborStaysWithinLimitIfCandidateJoins(neighbor, candidate, limit, adjacency, neighborBiomeOf));
    }

    /** Whether {@code neighbor}'s own same-biome-neighbor count would stay within {@code limit} after {@code faceIndex} (elsewhere) joins it as one more same-{@code candidate} neighbor - a no-op {@code true} if {@code neighbor} doesn't even hold {@code candidate} itself. */
    private static boolean neighborStaysWithinLimitIfCandidateJoins(
        int neighbor, BiomeId candidate, int limit, FaceAdjacency.Adjacency adjacency, java.util.function.IntFunction<BiomeId> neighborBiomeOf
    ) {
        if (!candidate.equals(neighborBiomeOf.apply(neighbor))) {
            return true;
        }
        int neighborCountExcludingFaceIndex = countNeighborsWithBiome(neighbor, candidate, adjacency, neighborBiomeOf);
        return neighborCountExcludingFaceIndex + 1 <= limit;
    }

    /** How many of {@code faceIndex}'s own neighbors currently hold {@code biome}, per {@code neighborBiomeOf}. */
    private static int countNeighborsWithBiome(
        int faceIndex, BiomeId biome, FaceAdjacency.Adjacency adjacency, java.util.function.IntFunction<BiomeId> neighborBiomeOf
    ) {
        int count = 0;
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            if (biome.equals(neighborBiomeOf.apply(neighbor))) {
                count++;
            }
        }
        return count;
    }

    /** Deterministic per-(worldSeed, faceIndex) shuffle — never a fixed (e.g. alphabetical) order, see class doc for the real bug this prevents. */
    private static List<BiomeId> candidatesInDeterministicOrder(List<BiomeId> domain, long worldSeed, int faceIndex) {
        long hash = worldSeed ^ CANDIDATE_ORDER_SALT;
        hash = hash * HASH_MULTIPLIER + faceIndex;
        Random random = new Random(hash);
        List<BiomeId> shuffled = new ArrayList<>(domain);
        Collections.shuffle(shuffled, random);
        return shuffled;
    }

    /** Jittered order, stable-sorted by current fairness deficit descending (so fairness/jitter still break ties within a shared deficit level). */
    private static List<BiomeId> candidatesInFairnessOrder(List<BiomeId> domain, long worldSeed, int faceIndex, FaceFairnessQuota fairness) {
        List<BiomeId> ordered = candidatesInDeterministicOrder(domain, worldSeed, faceIndex);
        ordered.sort(Comparator.comparingDouble(fairness::deficit).reversed());
        return ordered;
    }
}
