package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

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
 * Assigns each free triangle face (identified by index into a {@link TriangleAdjacency})
 * both a {@link BiomeId} AND a
 * target width in one pass — the "fused" replacement for the deleted {@code
 * CspIdentityAssignment} (adjacency-only, size deferred to a separate later
 * stage) and the deleted mesh/shape geometry realizers (which is where size
 * used to actually get decided). There is deliberately no cross-neighbor
 * size CONSTRAINT here: {@code AdjacencyRule} has no size-coupling field
 * anywhere in the data model (checked directly against the source before
 * writing this — a biome pair is either allowed or not, independent of
 * either side's size), so once a face's identity is legal, its width is a
 * free, deterministic pick from that biome's own {@link BiomeSizeRange} —
 * real geometric size consistency (does this triangle actually get enough
 * room given its neighbors' competing claims) is {@code SeamGrowthRasterizer}'s
 * job, not this one's — that class turns this width into a target CELL COUNT
 * and grows the real territory later, cell by cell, competing against every
 * other face for the same ground.
 *
 * <p>MRV (most-constrained-face-first) + maintained arc consistency
 * (AC-3-style propagation, {@link #forwardCheck}) during backtracking.
 * <b>Was plain single-hop forward checking</b> — correct back when this ran
 * over the deleted triangle mesh (each face had at most 3 neighbors, and the
 * doc here used to argue AC-3's extra pruning wasn't worth it at that
 * degree). That justification went stale the moment the mesh pivoted to hex
 * (see {@code HexMeshBuilder}): a hex face has up to 6 neighbors, and
 * because of the hex mesh's own vertex-degree-3 property (the exact
 * guarantee the whole pivot exists for), every pair of CONSECUTIVE
 * neighbors around a face is also directly adjacent to each other — the
 * real local constraint structure is a dense, near-clique neighborhood, not
 * 6 independent pairwise checks. Plain forward checking only re-examines
 * the face just assigned's own direct neighbors; it never looks further, so
 * a real conflict two or three faces away went completely undetected until
 * the search physically backtracked its way there. Measured directly: a
 * real 238-free-face mesh at {@code worldSeed=2034} against {@code
 * ModBiomeGraph.OVERWORLD} took 13 real seconds with plain forward checking
 * and was still running (300+ CPU-seconds, 222-deep recursion, confirmed
 * via a live thread dump) before propagation was added. {@link
 * #forwardCheck} now runs a real AC-3 worklist: whenever assigning a face
 * (or an earlier propagation step) shrinks some neighbor's domain, every
 * OTHER neighbor of that shrunk face gets re-revised too, and so on until
 * nothing changes — conflicts several hops away are pruned immediately
 * instead of being rediscovered node by node.
 *
 * <p><b>Binary AC-3 alone still wasn't enough.</b> Found by direct
 * measurement, not theory: {@code worldSeed=2060} against {@code
 * ModBiomeGraph.OVERWORLD} at {@code HEX_SIZE_BLOCKS=325} thrashed for 60+
 * seconds with binary propagation alone, recursion depth pinned flat around
 * 230/238 faces the whole time (real CPU burn, not a hang) — captured its
 * exact residual state directly: 3 mutually-adjacent faces (a real hex
 * vertex) all narrowed to the same 4-value domain, whose only two legal
 * pairs were disjoint (no biome legal with members of BOTH pairs) — a real,
 * provable dead end with zero valid triples, that binary arc consistency
 * cannot see because it only ever asks "does X have some compatible value
 * in Y" one pair at a time, never "does some value work against BOTH Y and
 * Z simultaneously." {@link #forwardCheck} now also runs ternary
 * consistency ({@link #reviseTriple}) over every real mesh-vertex triple
 * ({@link #mutualNeighborTrianglesByFace}, derived purely from the
 * adjacency graph — 3 mutually-adjacent faces IS a mesh vertex, by {@code
 * HexMeshBuilder}'s own vertex-degree-3 guarantee, no separate geometry
 * needed), interleaved with binary propagation to one joint fixed point
 * (a shrink from either kind can enable further shrinks of the other).
 * Considered and rejected: encoding this as a {@code BiomeGraph}
 * construction-time rule instead — checked directly, every one of the 262
 * real legal pairs in {@code OVERWORLD} already extends to at least one
 * real triangle somewhere in the graph (zero exceptions), including both
 * pairs from the {@code worldSeed=2060} dead end. The graph itself was
 * never deficient; the specific triangle's domain got narrowed down to
 * exactly those two disjoint pairs by unrelated legality pressure from each
 * face's OTHER neighbors, a purely dynamic, per-instance narrowing no
 * static graph property can predict or rule out in advance.
 *
 * <p>{@link #backtrack} also enforces a hard node budget ({@link
 * SearchBudget}) as a safety net independent of how good propagation is —
 * some instance can still be genuinely hard, and "hangs indefinitely" is
 * strictly worse than "fails loud with a diagnosis" on a live game path.
 * Not a primary fix, propagation above is; this exists because no
 * propagation scheme eliminates worst-case blowup on every possible
 * instance.
 *
 * <p>Candidate biomes are tried
 * in a per-{@code (worldSeed, faceIndex)} DETERMINISTIC JITTERED order, never
 * a fixed order — a real, previously-shipped bug in this codebase's earlier
 * CSP attempt tried candidates in plain alphabetical order, so any
 * under-constrained face always picked the same first-alphabetical biome and
 * never backtracked, collapsing a whole real-scale region to one biome
 * (invisible in small hand-built test fixtures, only surfaced at real
 * scale — see {@code diverseUnconstrainedMeshDoesNotCollapseToOneBiome}).
 *
 * <p>No retry/reseed/node-drop ladder here on purpose — that's an
 * orchestration concern (mirrors the deleted {@code TopologyFirstRegionSolver}'s
 * own split from {@code CspIdentityAssignment}), belongs in whatever calls
 * this once a real orchestrator exists. This class just reports success or
 * failure for the exact mesh it's given.
 *
 * <p>Candidate order is also fairness-biased, not just jittered — see {@link
 * FaceFairnessQuota} for the real rich-get-richer skew this fixes, the quota
 * derivation, and the proof it works today. At every choice point, legal candidates
 * are ranked by current deficit descending, so an under-represented biome is preferred
 * over an already-quota-filled one whenever both are equally legal; the seeded jitter
 * from {@link #candidatesInDeterministicOrder} still breaks ties and keeps the result
 * deterministic. This never overrides legality or backtracking — it only reorders
 * choices that are already equally legal.
 */
public final class TriangleIdentitySizeAssignment {

    private static final long CANDIDATE_ORDER_SALT = 0x7A4C_3D1F_8B02_66E5L;
    private static final long SIZE_PICK_SALT = 0x1F5E_9C7A_44D0_B3A6L;

    public record Assignment(BiomeId biome, int widthBlocks) {
    }

    private TriangleIdentitySizeAssignment() {
    }

    /**
     * @param adjacency        face-to-face neighbor structure ({@link TriangleAdjacency}) —
     *                         the actual triangle geometry itself is never needed, only
     *                         which faces border which.
     * @param freeFaceIndices  which faces this call is responsible for assigning — never
     *                         includes anything hull-touching in whatever mesh the caller
     *                         built (see {@link TriangleAdjacency}'s own doc: a hull-touching
     *                         face is missing real neighbor context, not this class's problem
     *                         to guess around).
     * @param fixedFaceBiomes  already-committed neighbor context (e.g. an adjacent,
     *                         already-solved area) — read-only ground truth, never
     *                         reassigned. Widths for fixed faces are the caller's own
     *                         concern; this class only ever needs their identity.
     * @return empty if no legal assignment exists for the given mesh/fixed context —
     *         {@code BiomeGraph}'s own {@code requireEveryLocalNeighborhoodSolvable}
     *         only rules out "impossible in principle" configs, not every real local
     *         arrangement.
     */
    public static Optional<Map<Integer, Assignment>> solve(
        TriangleAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices,
        Map<Integer, BiomeId> fixedFaceBiomes, BiomeGraph graph, long worldSeed
    ) {
        return solve(adjacency, freeFaceIndices, fixedFaceBiomes, graph, worldSeed, Double.POSITIVE_INFINITY);
    }

    /**
     * Same as {@link #solve(TriangleAdjacency.Adjacency, Set, Map, BiomeGraph, long)}, but
     * also caps how many triangles any ONE biome can be handed in this region — see {@link
     * FaceFairnessQuota} for why this exists and how the cap is derived. Callers that
     * don't have (or don't need) region-area awareness can keep using the simpler overload,
     * which passes {@link Double#POSITIVE_INFINITY} here (no cap, today's exact behavior).
     *
     * @param regionAreaBlocks2 the region's own area in blocks², used only to size the cap.
     */
    public static Optional<Map<Integer, Assignment>> solve(
        TriangleAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices,
        Map<Integer, BiomeId> fixedFaceBiomes, BiomeGraph graph, long worldSeed, double regionAreaBlocks2
    ) {
        // .sorted(), not just .toList() - BiomeGraph.nodes() comes from a Map.copyOf-wrapped
        // map internally, and the JDK deliberately randomizes Map.copyOf's iteration order
        // PER JVM RUN (specifically to catch code that wrongly assumes it's stable). Shuffling
        // an already-JVM-random starting order with an otherwise-correctly-seeded Random still
        // produces a JVM-run-dependent result, since Fisher-Yates shuffle's output depends on
        // the input arrangement, not just the seed
        List<BiomeId> allBiomes = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        Map<Integer, List<BiomeId>> domains = new HashMap<>();
        for (int faceIndex : freeFaceIndices) {
            List<BiomeId> domain = new ArrayList<>(allBiomes);
            domain.removeIf(candidate -> !legalAgainstFixedNeighbors(faceIndex, candidate, adjacency, fixedFaceBiomes, graph));
            domains.put(faceIndex, domain);
        }

        FaceFairnessQuota fairness = FaceFairnessQuota.forRegion(allBiomes, freeFaceIndices.size(), regionAreaBlocks2);
        Map<Integer, BiomeId> assigned = new LinkedHashMap<>();
        SearchBudget budget = SearchBudget.forFaceCount(freeFaceIndices.size());
        Map<Integer, List<int[]>> mutualNeighborTrianglesByFace = mutualNeighborTrianglesByFace(adjacency, freeFaceIndices);
        if (!backtrack(domains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness, budget, mutualNeighborTrianglesByFace)) {
            return Optional.empty();
        }

        Map<Integer, Assignment> result = new LinkedHashMap<>();
        assigned.forEach((faceIndex, biome) -> result.put(faceIndex, new Assignment(biome, pickWidth(graph.node(biome).sizeRange(), worldSeed, faceIndex, biome))));
        return Optional.of(result);
    }

    private static boolean backtrack(
        Map<Integer, List<BiomeId>> domains, Map<Integer, BiomeId> assigned, TriangleAdjacency.Adjacency adjacency,
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
            if (!legalAgainstAssignedNeighbors(faceIndex, candidate, adjacency, assigned, graph)) {
                continue;
            }

            assigned.put(faceIndex, candidate);
            fairness.increment(candidate);
            Map<Integer, List<BiomeId>> prunedDomains = forwardCheck(
                faceIndex, candidate, domains, adjacency, freeFaceIndices, assigned, graph, mutualNeighborTrianglesByFace
            );
            if (prunedDomains != null
                && backtrack(prunedDomains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness, budget, mutualNeighborTrianglesByFace)) {
                return true;
            }
            fairness.decrement(candidate);
            assigned.remove(faceIndex);
        }
        return false;
    }

    /**
     * AC-3-style arc-consistency propagation (binary: {@link #reviseAgainstSingleValue}/
     * {@link #reviseAgainstDomain}) PLUS mesh-vertex triple-consistency (ternary: {@link
     * #reviseTriple}) run to a joint fixed point — see class doc for why binary
     * consistency alone isn't enough. Binary arc consistency only ever asks "does face X
     * have SOME compatible value in face Y's domain" one pair at a time; it can leave a
     * domain looking perfectly fine against every neighbor INDIVIDUALLY while having no
     * value that works against ALL of a real mesh-vertex triple SIMULTANEOUSLY - the exact
     * gap measured directly on {@code worldSeed=2060} at {@code HEX_SIZE_BLOCKS=325}: 3
     * mutually-adjacent faces (a real hex vertex, guaranteed to exist by {@code
     * HexMeshBuilder}'s own vertex-degree-3 property) each independently kept candidates
     * that, taken together, formed no legal triple at all (their shared 4-value domain's
     * only two legal pairs were disjoint - no 3-clique existed). A shrink from either kind
     * of revision can enable further shrinks of the OTHER kind, so both worklists are
     * drained together in one loop until NEITHER changes anything, not run once each.
     *
     * @return a copy of {@code domains} with every domain reachable by propagation pruned
     * to values consistent with every neighbor's own domain (binary) AND every real
     * mesh-vertex triple it participates in (ternary), or {@code null} if propagation
     * empties any domain (a dead end, detected as early as this level of consistency can
     * detect it - still not a completeness guarantee, see {@link SearchBudget}).
     */
    private static Map<Integer, List<BiomeId>> forwardCheck(
        int faceIndex, BiomeId candidate, Map<Integer, List<BiomeId>> domains, TriangleAdjacency.Adjacency adjacency,
        Set<Integer> freeFaceIndices, Map<Integer, BiomeId> assigned, BiomeGraph graph,
        Map<Integer, List<int[]>> mutualNeighborTrianglesByFace
    ) {
        Map<Integer, List<BiomeId>> next = new HashMap<>(domains);
        Deque<Integer> binaryWorklist = new ArrayDeque<>();
        Deque<int[]> triangleWorklist = new ArrayDeque<>();

        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            if (!freeFaceIndices.contains(neighbor) || assigned.containsKey(neighbor)) {
                continue;
            }
            if (!reviseAgainstSingleValue(neighbor, candidate, next, graph, binaryWorklist)) {
                return null;
            }
        }
        enqueueTrianglesOf(faceIndex, mutualNeighborTrianglesByFace, assigned, triangleWorklist);

        while (!binaryWorklist.isEmpty() || !triangleWorklist.isEmpty()) {
            while (!binaryWorklist.isEmpty()) {
                int shrunk = binaryWorklist.poll();
                List<BiomeId> shrunkDomain = next.get(shrunk);
                for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(shrunk, Set.of())) {
                    if (!freeFaceIndices.contains(neighbor) || assigned.containsKey(neighbor)) {
                        continue;
                    }
                    if (!reviseAgainstDomain(neighbor, shrunkDomain, next, graph, binaryWorklist)) {
                        return null;
                    }
                }
                enqueueTrianglesOf(shrunk, mutualNeighborTrianglesByFace, assigned, triangleWorklist);
            }

            if (!triangleWorklist.isEmpty()) {
                int[] triangle = triangleWorklist.poll();
                Set<Integer> shrunkByTriple = reviseTriple(triangle, next, graph);
                if (shrunkByTriple == null) {
                    return null;
                }
                for (int shrunkFace : shrunkByTriple) {
                    binaryWorklist.add(shrunkFace);
                    enqueueTrianglesOf(shrunkFace, mutualNeighborTrianglesByFace, assigned, triangleWorklist);
                }
            }
        }
        return next;
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
     * Ternary consistency for one real mesh-vertex triple {@code (a, b, c)} (all three
     * mutually adjacent - see {@link #mutualNeighborTrianglesByFace}): prunes each face's
     * domain to only values that have SOME simultaneous, mutually-legal completion in the
     * OTHER TWO faces' current domains - a genuine 3-way check binary arc consistency
     * cannot express, since it only ever reasons about one pair at a time. This is the
     * exact gap that let {@code worldSeed=2060}'s dead end (see class doc) go undetected
     * until brute chronological backtracking eventually rediscovered it.
     *
     * @return the set of faces (among {@code a, b, c}) whose domain actually shrank, or
     * {@code null} if any domain emptied (a dead end - no legal joint assignment exists
     * for this triple given the other two's current domains).
     */
    private static Set<Integer> reviseTriple(int[] triangle, Map<Integer, List<BiomeId>> domains, BiomeGraph graph) {
        int a = triangle[0];
        int b = triangle[1];
        int c = triangle[2];
        List<BiomeId> domainA = domains.get(a);
        List<BiomeId> domainB = domains.get(b);
        List<BiomeId> domainC = domains.get(c);

        List<BiomeId> revisedA = domainA.stream().filter(value -> hasLegalCompletion(value, domainB, domainC, graph)).toList();
        List<BiomeId> revisedB = domainB.stream().filter(value -> hasLegalCompletion(value, domainA, domainC, graph)).toList();
        List<BiomeId> revisedC = domainC.stream().filter(value -> hasLegalCompletion(value, domainA, domainB, graph)).toList();
        if (revisedA.isEmpty() || revisedB.isEmpty() || revisedC.isEmpty()) {
            return null;
        }

        Set<Integer> shrunk = new java.util.HashSet<>();
        if (revisedA.size() != domainA.size()) {
            domains.put(a, revisedA);
            shrunk.add(a);
        }
        if (revisedB.size() != domainB.size()) {
            domains.put(b, revisedB);
            shrunk.add(b);
        }
        if (revisedC.size() != domainC.size()) {
            domains.put(c, revisedC);
            shrunk.add(c);
        }
        return shrunk;
    }

    /** Whether some pair (one value from each of {@code domainX}, {@code domainY}) is mutually legal AND both legal against {@code value} - the actual joint 3-way check {@link #reviseTriple} needs. */
    private static boolean hasLegalCompletion(BiomeId value, List<BiomeId> domainX, List<BiomeId> domainY, BiomeGraph graph) {
        for (BiomeId x : domainX) {
            if (!graph.isAllowed(value, x)) {
                continue;
            }
            for (BiomeId y : domainY) {
                if (graph.isAllowed(value, y) && graph.isAllowed(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Every real mesh-vertex triple among free faces ({@link
     * TriangleAdjacency.Adjacency#mutuallyAdjacentTriples}), indexed by each member face
     * for cheap "which triangles does this face's shrink affect" lookups. Computed once
     * per {@link #solve} call (the adjacency structure never changes during backtracking,
     * only the domains do), not per search node.
     */
    private static Map<Integer, List<int[]>> mutualNeighborTrianglesByFace(TriangleAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices) {
        Map<Integer, List<int[]>> byFace = new HashMap<>();
        for (int[] triangle : adjacency.mutuallyAdjacentTriples(freeFaceIndices)) {
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

    private static boolean legalAgainstFixedNeighbors(int faceIndex, BiomeId candidate, TriangleAdjacency.Adjacency adjacency, Map<Integer, BiomeId> fixedFaceBiomes, BiomeGraph graph) {
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            BiomeId fixedNeighbor = fixedFaceBiomes.get(neighbor);
            if (fixedNeighbor != null && !graph.isAllowed(candidate, fixedNeighbor)) {
                return false;
            }
        }
        return true;
    }

    private static boolean legalAgainstAssignedNeighbors(int faceIndex, BiomeId candidate, TriangleAdjacency.Adjacency adjacency, Map<Integer, BiomeId> assigned, BiomeGraph graph) {
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            BiomeId assignedNeighbor = assigned.get(neighbor);
            if (assignedNeighbor != null && !graph.isAllowed(candidate, assignedNeighbor)) {
                return false;
            }
        }
        return true;
    }

    /** Deterministic per-(worldSeed, faceIndex) shuffle — never a fixed (e.g. alphabetical) order, see class doc for the real bug this prevents. */
    private static List<BiomeId> candidatesInDeterministicOrder(List<BiomeId> domain, long worldSeed, int faceIndex) {
        long hash = worldSeed ^ CANDIDATE_ORDER_SALT;
        hash = hash * 31 + faceIndex;
        Random random = new Random(hash);
        List<BiomeId> shuffled = new ArrayList<>(domain);
        Collections.shuffle(shuffled, random);
        return shuffled;
    }

    /** Jittered order, then stable-sorted by current fairness deficit descending — see class doc's fairness section. Sort is stable so equal-deficit candidates keep the jittered tie-break. */
    private static List<BiomeId> candidatesInFairnessOrder(List<BiomeId> domain, long worldSeed, int faceIndex, FaceFairnessQuota fairness) {
        List<BiomeId> ordered = candidatesInDeterministicOrder(domain, worldSeed, faceIndex);
        ordered.sort(Comparator.comparingDouble(fairness::deficit).reversed());
        return ordered;
    }

    private static int pickWidth(BiomeSizeRange range, long worldSeed, int faceIndex, BiomeId biome) {
        if (range.minWidthBlocks() == range.maxWidthBlocks()) {
            return range.minWidthBlocks();
        }
        long hash = worldSeed ^ SIZE_PICK_SALT;
        hash = hash * 31 + faceIndex;
        hash = hash * 31 + biome.hashCode();
        int span = range.maxWidthBlocks() - range.minWidthBlocks() + 1;
        return range.minWidthBlocks() + new Random(hash).nextInt(span);
    }

    /**
     * Hard ceiling on {@link #backtrack} node visits — a safety net independent of how
     * good {@link #forwardCheck}'s own propagation is, not a replacement for it (see class
     * doc). {@code NODES_PER_FACE} was calibrated by direct measurement, not guessed: a
     * real 238-free-face mesh's worst KNOWN-solvable instance (across the seeds already
     * exercised by this codebase's own stress tests, post-propagation-upgrade) visits on
     * the order of a few hundred nodes; {@code worldSeed=2034} against {@code
     * ModBiomeGraph.OVERWORLD} at {@code HEX_SIZE_BLOCKS=400} - the exact instance that
     * originally motivated this class - is the one real pre-propagation pathological case
     * on record, and now solves in well under the ceiling below post-propagation-upgrade.
     * 5,000 nodes/face leaves generous headroom above every measured legitimate solve
     * while still failing loud in well under a second of wall-clock time for a mesh this
     * size, rather than hanging indefinitely.
     */
    private static final class SearchBudget {
        private static final long NODES_PER_FACE = 5_000L;

        private final long maxNodes;
        private long visitedNodes;

        private SearchBudget(long maxNodes) {
            this.maxNodes = maxNodes;
        }

        static SearchBudget forFaceCount(int freeFaceCount) {
            return new SearchBudget(Math.max(1, freeFaceCount) * NODES_PER_FACE);
        }

        void visitNode(int freeFaceCount) {
            visitedNodes++;
            if (visitedNodes > maxNodes) {
                throw new IllegalStateException(
                    "TriangleIdentitySizeAssignment: backtracking exceeded " + maxNodes + " nodes ("
                        + NODES_PER_FACE + "/face x " + freeFaceCount + " free faces) without finding or ruling out "
                        + "a legal assignment - a genuinely pathological instance (or a real algorithmic regression), "
                        + "not something to wait out. Fails loud instead of hanging indefinitely."
                );
            }
        }
    }
}
