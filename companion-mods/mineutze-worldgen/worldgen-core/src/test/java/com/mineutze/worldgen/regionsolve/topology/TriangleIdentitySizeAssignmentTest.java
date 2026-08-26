package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangleIdentitySizeAssignmentTest {

    private static BiomeId id(String path) {
        return new BiomeId("test", path);
    }

    private static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeNode(
            id(path), new BiomeSizeRange(minWidthBlocks, maxWidthBlocks),
            new BiomeShapeHints(0.5, 0.5, 0.5), GrowthCohesionProfile.UNRESTRICTED
        );
    }

    /**
     * The real bug this regression-tests for: {@code BiomeGraph.nodes()} comes from a
     * {@code Map.copyOf}-wrapped map, and the JDK deliberately randomizes {@code
     * Map.copyOf}'s iteration order PER JVM RUN. A worldSeed=42 run of the real region
     * solver produced completely different biome distributions across separate process
     * launches (same JVM-internal test run always agreed with itself, which is why this
     * wasn't caught by a plain "solve twice, compare" test) until the candidate list got
     * sorted into a canonical order before being shuffled - constructing the graph with
     * its nodes in a different order is the closest thing a single-process test can do to
     * reproduce that same symptom.
     */
    @Test
    void resultDoesNotDependOnBiomeGraphsConstructorNodeOrder() {
        TriangleAdjacency.Adjacency adjacency = realAdjacency(2026L, 300, 6000);
        Set<Integer> free = interiorFaceIndices(adjacency);

        List<AdjacencyRule> rules = List.of(
            AdjacencyRule.allow(id("desert"), id("savanna"), 10),
            AdjacencyRule.allow(id("savanna"), id("plains"), 10)
        );
        BiomeGraph forwardOrder = new BiomeGraph(List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200)), rules);
        BiomeGraph reverseOrder = new BiomeGraph(List.of(node("plains", 100, 200), node("savanna", 200, 300), node("desert", 200, 300)), rules);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> fromForwardOrder =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), forwardOrder, 2026L).orElseThrow();
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> fromReverseOrder =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), reverseOrder, 2026L).orElseThrow();

        assertEquals(fromForwardOrder, fromReverseOrder);
    }

    @Test
    void sameInputsProduceTheSameAssignment() {
        TriangleAdjacency.Adjacency adjacency = realAdjacency(1L, 400, 3000);
        Set<Integer> free = interiorFaceIndices(adjacency);
        BiomeGraph graph = threeBiomeChainGraph();

        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> first = TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 55L);
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> second = TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 55L);
        assertEquals(first, second);
    }

    @Test
    void everyAssignedFaceIsLegalAgainstItsAssignedAndFixedNeighbors() {
        TriangleAdjacency.Adjacency adjacency = realAdjacency(9L, 350, 4000);
        Set<Integer> free = interiorFaceIndices(adjacency);
        BiomeGraph graph = threeBiomeChainGraph();

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 9L).orElseThrow();

        for (Map.Entry<Integer, TriangleIdentitySizeAssignment.Assignment> entry : assignment.entrySet()) {
            for (int neighbor : adjacency.neighborFaceIndices().get(entry.getKey())) {
                TriangleIdentitySizeAssignment.Assignment neighborAssignment = assignment.get(neighbor);
                if (neighborAssignment != null) {
                    assertTrue(
                        graph.isAllowed(entry.getValue().biome(), neighborAssignment.biome()),
                        "face " + entry.getKey() + " (" + entry.getValue().biome() + ") illegally touches face " + neighbor + " (" + neighborAssignment.biome() + ")"
                    );
                }
            }
        }
    }

    @Test
    void everyAssignedWidthIsWithinItsOwnBiomesSizeRange() {
        TriangleAdjacency.Adjacency adjacency = realAdjacency(21L, 350, 4000);
        Set<Integer> free = interiorFaceIndices(adjacency);
        BiomeGraph graph = threeBiomeChainGraph();

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 21L).orElseThrow();

        for (TriangleIdentitySizeAssignment.Assignment value : assignment.values()) {
            BiomeSizeRange range = graph.node(value.biome()).sizeRange();
            assertTrue(value.widthBlocks() >= range.minWidthBlocks() && value.widthBlocks() <= range.maxWidthBlocks());
        }
    }

    @Test
    void respectsFixedNeighborContext() {
        // One free face with one fixed neighbor pinned to "anchor". A candidate equal to
        // "anchor" itself is always trivially legal (BiomeGraph: same biome is always
        // allowed next to itself) - so this test needs a candidate that's illegal for a
        // REAL reason (no rule, not bridgeable in one hop) to prove the pruning actually
        // excludes something, not just "anchor" copied everywhere by coincidence.
        // "incompatible" has no direct rule with "anchor" (only bridges through
        // "compatible", which the direct-legality check never uses) - it must never be
        // chosen for the free face.
        TriangleAdjacency.Adjacency adjacency = new TriangleAdjacency.Adjacency(Map.of(0, Set.of(99)), Set.of());
        Set<Integer> free = Set.of(0);
        Map<Integer, BiomeId> fixed = Map.of(99, id("anchor"));

        BiomeGraph graph = new BiomeGraph(
            List.of(node("anchor", 100, 200), node("compatible", 100, 200), node("incompatible", 100, 200)),
            List.of(
                AdjacencyRule.allow(id("anchor"), id("compatible"), 10),
                AdjacencyRule.allow(id("compatible"), id("incompatible"), 10)
            )
        );

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment =
            TriangleIdentitySizeAssignment.solve(adjacency, free, fixed, graph, 3L).orElseThrow();
        assertTrue(
            Set.of(id("anchor"), id("compatible")).contains(assignment.get(0).biome()),
            "picked " + assignment.get(0).biome() + ", which is not directly legal next to the fixed \"anchor\" neighbor"
        );
    }

    @Test
    void reportsEmptyWhenNoBiomeIsLegalAgainstAllFixedNeighbors() {
        // Face 0 touches three fixed neighbors: a, b, c. "bridge" connects to b, c, and
        // onlyLegalWithA (purely so BiomeGraph's own construction-time bridgeability check
        // passes - every biome pair needs a path within 3 hops) but NOT to a directly, so
        // it's still illegal against fixed "a". No candidate (including a/b/c themselves,
        // each only trivially legal against ITS OWN fixed copy) is simultaneously legal
        // against all three fixed neighbors at once.
        TriangleAdjacency.Adjacency adjacency = new TriangleAdjacency.Adjacency(Map.of(0, Set.of(10, 11, 12)), Set.of());
        Set<Integer> free = Set.of(0);
        Map<Integer, BiomeId> fixed = Map.of(10, id("a"), 11, id("b"), 12, id("c"));

        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 200), node("b", 100, 200), node("c", 100, 200), node("onlyLegalWithA", 100, 200), node("bridge", 100, 200)),
            List.of(
                AdjacencyRule.allow(id("b"), id("c"), 10),
                AdjacencyRule.allow(id("a"), id("onlyLegalWithA"), 10),
                AdjacencyRule.allow(id("bridge"), id("onlyLegalWithA"), 10),
                AdjacencyRule.allow(id("bridge"), id("b"), 10),
                AdjacencyRule.allow(id("bridge"), id("c"), 10)
            )
        );

        assertEquals(Optional.empty(), TriangleIdentitySizeAssignment.solve(adjacency, free, fixed, graph, 1L));
    }

    /**
     * The exact real bug this class's own doc names: an earlier CSP attempt in this
     * codebase tried candidates in a FIXED order, so every under-constrained face (no
     * fixed neighbor pinning it) always picked the same first candidate and never
     * backtracked, collapsing a whole real-scale mesh onto one biome - invisible in a
     * small hand-built fixture, only visible at real scale with zero fixed context.
     */
    @Test
    void diverseUnconstrainedMeshDoesNotCollapseToOneBiome() {
        TriangleAdjacency.Adjacency adjacency = realAdjacency(2026L, 300, 6000);
        Set<Integer> free = interiorFaceIndices(adjacency);
        assertTrue(free.size() > 50, "expected a real-sized mesh to exercise this on, got " + free.size() + " interior faces");

        BiomeGraph graph = threeBiomeChainGraph();
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 2026L).orElseThrow();

        Set<BiomeId> distinctBiomes = new HashSet<>();
        assignment.values().forEach(value -> distinctBiomes.add(value.biome()));
        assertTrue(distinctBiomes.size() > 1, "collapsed to a single biome (" + distinctBiomes + ") across " + assignment.size() + " faces");
    }

    private static TriangleAdjacency.Adjacency realAdjacency(long worldSeed, int spacingBlocks, int span) {
        List<Point2D> points = SeedGrid.seedsIn(worldSeed, spacingBlocks, 0, 0, span, span);
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);
        return TriangleAdjacency.of(faces);
    }

    private static Set<Integer> interiorFaceIndices(TriangleAdjacency.Adjacency adjacency) {
        Set<Integer> interior = new LinkedHashSet<>();
        for (int faceIndex : adjacency.neighborFaceIndices().keySet()) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                interior.add(faceIndex);
            }
        }
        return interior;
    }

    private static BiomeGraph threeBiomeChainGraph() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10)
            )
        );
    }
}
