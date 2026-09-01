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
        if (!backtrack(domains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness, budget)) {
            return Optional.empty();
        }

        Map<Integer, Assignment> result = new LinkedHashMap<>();
        assigned.forEach((faceIndex, biome) -> result.put(faceIndex, new Assignment(biome, pickWidth(graph.node(biome).sizeRange(), worldSeed, faceIndex, biome))));
        return Optional.of(result);
    }

    private static boolean backtrack(
        Map<Integer, List<BiomeId>> domains, Map<Integer, BiomeId> assigned, TriangleAdjacency.Adjacency adjacency,
        Set<Integer> freeFaceIndices, BiomeGraph graph, long worldSeed, FaceFairnessQuota fairness, SearchBudget budget
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
            Map<Integer, List<BiomeId>> prunedDomains = forwardCheck(faceIndex, candidate, domains, adjacency, freeFaceIndices, assigned, graph);
            if (prunedDomains != null && backtrack(prunedDomains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness, budget)) {
                return true;
            }
            fairness.decrement(candidate);
            assigned.remove(faceIndex);
        }
        return false;
    }

    /**
     * AC-3-style arc-consistency propagation, not just single-hop forward checking (see
     * class doc for why plain forward checking stopped being enough once the mesh pivoted
     * to hex). Seeds a worklist with {@code faceIndex}'s own unassigned neighbors (the
     * direct effect of the new assignment); every time revising a face's domain against
     * one of its neighbors actually shrinks it, every OTHER unassigned neighbor of THAT
     * face gets re-revised too, and so on until the worklist drains - a conflict several
     * hops away is pruned immediately instead of only being discovered once the search
     * physically backtracks its way there.
     *
     * @return a copy of {@code domains} with every domain reachable by propagation pruned
     * to values still consistent with at least one value in each neighbor's own domain, or
     * {@code null} if propagation empties any domain (a dead end, detected as early as
     * arc consistency alone can detect it).
     */
    private static Map<Integer, List<BiomeId>> forwardCheck(
        int faceIndex, BiomeId candidate, Map<Integer, List<BiomeId>> domains, TriangleAdjacency.Adjacency adjacency,
        Set<Integer> freeFaceIndices, Map<Integer, BiomeId> assigned, BiomeGraph graph
    ) {
        Map<Integer, List<BiomeId>> next = new HashMap<>(domains);
        Deque<Integer> worklist = new ArrayDeque<>();

        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            if (!freeFaceIndices.contains(neighbor) || assigned.containsKey(neighbor)) {
                continue;
            }
            if (!reviseAgainstSingleValue(neighbor, candidate, next, graph, worklist)) {
                return null;
            }
        }

        while (!worklist.isEmpty()) {
            int shrunk = worklist.poll();
            List<BiomeId> shrunkDomain = next.get(shrunk);
            for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(shrunk, Set.of())) {
                if (!freeFaceIndices.contains(neighbor) || assigned.containsKey(neighbor)) {
                    continue;
                }
                if (!reviseAgainstDomain(neighbor, shrunkDomain, next, graph, worklist)) {
                    return null;
                }
            }
        }
        return next;
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
