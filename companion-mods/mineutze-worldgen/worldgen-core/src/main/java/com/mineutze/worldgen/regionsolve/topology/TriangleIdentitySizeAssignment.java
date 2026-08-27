package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * <p>Each face has AT MOST 3 real neighbors ({@link TriangleAdjacency}'s own
 * guarantee) — MRV (most-constrained-face-first) + forward checking
 * backtracking, not full AC-3 arc-consistency: at degree ≤3 the extra
 * pruning power AC-3 buys over forward checking is small, and forward
 * checking is meaningfully simpler to get right. Candidate biomes are tried
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
 * <p>Candidate order is also fairness-biased, not just jittered: legality-only
 * random picking let {@code BiomeGraph} topology (a biome's own degree/how
 * "central" it is in the adjacency graph) drive systematic rich-get-richer
 * triangle-count skew, independent of graph size — verified empirically both
 * on a 6-biome graph (ocean=61 vs badlands=6 of 182 free faces) and a 12-biome
 * graph (windswept_hills=44 vs desert=2 of 178, with ocean=0 entirely absent).
 * Each biome gets a target quota — its {@link BiomeSizeRange} midpoint
 * SQUARED (final territory is an area, grown cell by cell later, not a raw
 * count, so the fair share should scale with area, not width) — as a
 * fraction of total free faces. At every choice point, legal
 * candidates are ranked by current deficit (quota minus how many faces
 * they've already been given) descending, so an under-represented biome is
 * preferred over an already-quota-filled one whenever both are equally legal;
 * the seeded jitter from {@link #candidatesInDeterministicOrder} still breaks
 * ties and keeps the result deterministic. This never overrides legality or
 * backtracking — it only reorders choices that are already equally legal.
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
     *         the graph's own construction-time bridgeability check ({@link
     *         BiomeGraph#node}'s caller doc) only rules out "impossible in principle"
     *         configs, not every real local arrangement.
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
     * #maxTrianglesPerBiome}'s own doc for why this exists: the plain triangle-count quota
     * ({@link #fairShareWeights}) is proportional to MESH DENSITY (how many free faces the
     * triangulation happens to produce), which has no relationship to how many REAL,
     * separate, sensibly-sized patches a biome could physically have in a region this size.
     * {@code regionAreaBlocks2} is what lets this method tell the two apart. Callers that
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
        // the input arrangement, not just the seed - a real bug caught by running the same
        // worldSeed across separate process launches, not visible within a single JVM's own
        // test run (see TriangleFieldGeometryRealizationTest's own determinism test, which
        // passed throughout because it only ever compared two solves in the SAME process).
        List<BiomeId> allBiomes = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        Map<Integer, List<BiomeId>> domains = new HashMap<>();
        for (int faceIndex : freeFaceIndices) {
            List<BiomeId> domain = new ArrayList<>(allBiomes);
            domain.removeIf(candidate -> !legalAgainstFixedNeighbors(faceIndex, candidate, adjacency, fixedFaceBiomes, graph));
            domains.put(faceIndex, domain);
        }

        Map<BiomeId, Double> weights = fairShareWeights(allBiomes, graph);
        int capPerBiome = maxTrianglesPerBiome(regionAreaBlocks2, weights);
        FairnessTracker fairness = new FairnessTracker(weights, freeFaceIndices.size(), capPerBiome);
        Map<Integer, BiomeId> assigned = new LinkedHashMap<>();
        if (!backtrack(domains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness)) {
            return Optional.empty();
        }

        Map<Integer, Assignment> result = new LinkedHashMap<>();
        assigned.forEach((faceIndex, biome) -> result.put(faceIndex, new Assignment(biome, pickWidth(graph.node(biome).sizeRange(), worldSeed, faceIndex, biome))));
        return Optional.of(result);
    }

    private static boolean backtrack(
        Map<Integer, List<BiomeId>> domains, Map<Integer, BiomeId> assigned, TriangleAdjacency.Adjacency adjacency,
        Set<Integer> freeFaceIndices, BiomeGraph graph, long worldSeed, FairnessTracker fairness
    ) {
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
            if (prunedDomains != null && backtrack(prunedDomains, assigned, adjacency, freeFaceIndices, graph, worldSeed, fairness)) {
                return true;
            }
            fairness.decrement(candidate);
            assigned.remove(faceIndex);
        }
        return false;
    }

    /** @return a copy of {@code domains} with every still-unassigned neighbor of {@code faceIndex} pruned to only candidates still legal against the new assignment, or {@code null} if that pruning empties any domain (an immediate dead end, forward checking's whole point — fail fast instead of discovering it several levels deeper). */
    private static Map<Integer, List<BiomeId>> forwardCheck(
        int faceIndex, BiomeId candidate, Map<Integer, List<BiomeId>> domains, TriangleAdjacency.Adjacency adjacency,
        Set<Integer> freeFaceIndices, Map<Integer, BiomeId> assigned, BiomeGraph graph
    ) {
        Map<Integer, List<BiomeId>> next = new HashMap<>(domains);
        for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
            if (!freeFaceIndices.contains(neighbor) || assigned.containsKey(neighbor)) {
                continue;
            }
            List<BiomeId> prunedDomain = next.get(neighbor).stream().filter(other -> graph.isAllowed(candidate, other)).toList();
            if (prunedDomain.isEmpty()) {
                return null;
            }
            next.put(neighbor, prunedDomain);
        }
        return next;
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
    private static List<BiomeId> candidatesInFairnessOrder(List<BiomeId> domain, long worldSeed, int faceIndex, FairnessTracker fairness) {
        List<BiomeId> ordered = candidatesInDeterministicOrder(domain, worldSeed, faceIndex);
        ordered.sort(Comparator.comparingDouble(fairness::deficit).reversed());
        return ordered;
    }

    /** Each biome's target share of total free faces, proportional to its {@link BiomeSizeRange} midpoint SQUARED — final territory is an area, so fair share must scale with area, not width. */
    private static Map<BiomeId, Double> fairShareWeights(List<BiomeId> allBiomes, BiomeGraph graph) {
        Map<BiomeId, Double> weights = new LinkedHashMap<>();
        for (BiomeId biome : allBiomes) {
            BiomeSizeRange range = graph.node(biome).sizeRange();
            double midWidth = (range.minWidthBlocks() + range.maxWidthBlocks()) / 2.0;
            weights.put(biome, midWidth * midWidth);
        }
        return weights;
    }

    // How many extra same-biome patches (beyond one) a region is allowed to hand a single
    // biome, on top of that biome's own fair AREA share. See maxTrianglesPerBiome's own doc
    // for the full derivation - kept generous (not 1) because "multiple disconnected
    // patches of the same biome in a region" is expected, real behavior, not a bug.
    private static final double PATCH_COUNT_SLACK = 4.0;

    /**
     * How many triangles ANY ONE biome should be handed in this region, as an UPPER BOUND
     * — a real, measured architecture mismatch this fixes: {@link #fairShareWeights}'s own
     * quota is proportional to TOTAL FREE FACES (a MESH DENSITY number, driven by how
     * closely {@code SeedGrid} places points), which has no relationship to how many real,
     * separate, sensibly-sized patches a biome could ever physically fit in a region this
     * size. On a real production run this handed one narrow-range biome (badlands, 500-800
     * block target) 20 separate identity triangles in a single 2048-block region — a region
     * that can geometrically hold only a handful of 500-800-wide patches TOTAL, across every
     * biome combined. All 20 of that biome's triangles then fought each other (as well as
     * bigger neighbors) for the same sliver of space, and none of them reliably won enough
     * of it — the direct cause of a real crash in the geometry-realization rasterizer of
     * the time (that biome collapsing to isolated 1-2-cell islands with chaotic,
     * high-arity neighborhoods).
     *
     * <p>Derivation: a biome's fair AREA share of the region is {@code regionAreaBlocks2 *
     * weight / totalWeight}. Dividing that by the biome's OWN average patch footprint
     * ({@code weight}, since {@link #fairShareWeights} already defines weight as
     * midpoint-width SQUARED) gives "how many of my own average patches fit in my own fair
     * share" — and the {@code weight} in the numerator and denominator cancels, so this
     * comes out to the SAME number for every biome: {@code regionAreaBlocks2 / totalWeight}.
     * That's not a coincidence or a bug - fairness already scales each biome's area quota by
     * its own footprint, so "how many of my own patches fit in my own share" is naturally
     * uniform across biomes once you're fair by area in the first place. {@link
     * #PATCH_COUNT_SLACK} multiplies that up to allow a biome several real disconnected
     * patches (not just one), matching this codebase's own established expectation that
     * multiple patches of the same biome per region is normal.
     *
     * <p>A SOFT cap only — see {@link FairnessTracker#deficit}: it steers the CSP's own
     * candidate ordering away from an already-capped biome, but never removes it from a
     * face's legal domain, so this can never make an otherwise-solvable mesh unsolvable.
     */
    private static int maxTrianglesPerBiome(double regionAreaBlocks2, Map<BiomeId, Double> weights) {
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight <= 0 || Double.isInfinite(regionAreaBlocks2)) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(PATCH_COUNT_SLACK * regionAreaBlocks2 / totalWeight));
    }

    /** Tracks each biome's running deficit (target quota minus faces assigned so far) during backtracking, incremented/decremented in lockstep with {@code assigned} so it always reflects the current partial solution, including after backtracking undoes a choice. */
    private static final class FairnessTracker {
        private final Map<BiomeId, Double> quotas;
        private final Map<BiomeId, Integer> counts = new HashMap<>();
        private final int capPerBiome;

        FairnessTracker(Map<BiomeId, Double> weights, int totalFreeFaces, int capPerBiome) {
            double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            quotas = new HashMap<>();
            weights.forEach((biome, weight) -> quotas.put(biome, weight / totalWeight * totalFreeFaces));
            this.capPerBiome = capPerBiome;
        }

        /** Never overrides legality or backtracking (see this class's own doc) - a capped biome just sorts last among still-legal candidates, so it can still be picked if it's genuinely the only legal option left. */
        double deficit(BiomeId biome) {
            if (counts.getOrDefault(biome, 0) >= capPerBiome) {
                return Double.NEGATIVE_INFINITY;
            }
            return quotas.getOrDefault(biome, 0.0) - counts.getOrDefault(biome, 0);
        }

        void increment(BiomeId biome) {
            counts.merge(biome, 1, Integer::sum);
        }

        void decrement(BiomeId biome) {
            counts.merge(biome, -1, Integer::sum);
        }
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
}
