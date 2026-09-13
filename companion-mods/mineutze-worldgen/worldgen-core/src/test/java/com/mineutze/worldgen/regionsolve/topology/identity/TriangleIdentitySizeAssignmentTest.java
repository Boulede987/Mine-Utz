package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.regionsolve.livepath.TriangleFieldGeometryRealization;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
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
        return new BiomeNode(id(path), new BiomeSizeRange(minWidthBlocks, maxWidthBlocks));
    }

    private static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks, int selfAdjacencyLimit) {
        return new BiomeNode(id(path), new BiomeSizeRange(minWidthBlocks, maxWidthBlocks), selfAdjacencyLimit);
    }

    /**
     * The real bug this regression-tests for: {@code BiomeGraph.nodes()} comes from a
     * {@code Map.copyOf}-wrapped map, and the JDK deliberately randomizes its iteration
     * order PER JVM RUN. A worldSeed=42 run of the real region solver produced completely
     * different biome distributions across separate process launches (same JVM-internal
     * test run always agreed with itself, why "solve twice, compare" didn't catch this)
     * until the candidate list got sorted before shuffling - constructing the graph with
     * nodes in a different order is the closest a single-process test can get to
     * reproducing that symptom.
     */
    @Test
    void resultDoesNotDependOnBiomeGraphsConstructorNodeOrder() {
        FaceAdjacency.Adjacency adjacency = realAdjacency(300, 6000);
        Set<Integer> free = interiorFaceIndices(adjacency);

        List<AdjacencyRule> rules = List.of(
            AdjacencyRule.allow(id("desert"), id("savanna"), 10),
            AdjacencyRule.allow(id("savanna"), id("plains"), 10),
            AdjacencyRule.allow(id("desert"), id("plains"), 10)
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
        FaceAdjacency.Adjacency adjacency = realAdjacency(400, 3000);
        Set<Integer> free = interiorFaceIndices(adjacency);
        BiomeGraph graph = threeBiomeChainGraph();

        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> first = TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 55L);
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> second = TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 55L);
        assertEquals(first, second);
    }

    @Test
    void everyAssignedFaceIsLegalAgainstItsAssignedAndFixedNeighbors() {
        FaceAdjacency.Adjacency adjacency = realAdjacency(350, 4000);
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
        FaceAdjacency.Adjacency adjacency = realAdjacency(350, 4000);
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
        // "anchor" is always trivially legal (BiomeGraph: same biome always allowed next
        // to itself) - so this test needs a candidate illegal for a REAL reason (no rule,
        // not bridgeable in one hop) to prove pruning actually excludes something, not
        // just "anchor" copied everywhere by coincidence. "incompatible" has no direct
        // rule with "anchor" (only bridges through "compatible," never used by the direct-
        // legality check) - must never be chosen for the free face.
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(99)), Set.of());
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
        // onlyLegalWithA (purely so BiomeGraph's construction-time bridgeability check
        // passes - every biome pair needs a path within 3 hops) but NOT to a directly, so
        // still illegal against fixed "a". No candidate (including a/b/c themselves, each
        // only trivially legal against ITS OWN fixed copy) is simultaneously legal against
        // all three fixed neighbors at once.
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(10, 11, 12)), Set.of());
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
     * The exact real bug this class's doc names: an earlier CSP attempt tried candidates
     * in a FIXED order, so every under-constrained face (no fixed neighbor pinning it)
     * always picked the same first candidate, never backtracked, collapsing a whole
     * real-scale mesh onto one biome - invisible in a small hand-built fixture, only
     * visible at real scale with zero fixed context.
     */
    @Test
    void diverseUnconstrainedMeshDoesNotCollapseToOneBiome() {
        FaceAdjacency.Adjacency adjacency = realAdjacency(300, 6000);
        Set<Integer> free = interiorFaceIndices(adjacency);
        assertTrue(free.size() > 50, "expected a real-sized mesh to exercise this on, got " + free.size() + " interior faces");

        BiomeGraph graph = threeBiomeChainGraph();
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 2026L).orElseThrow();

        Set<BiomeId> distinctBiomes = new HashSet<>();
        assignment.values().forEach(value -> distinctBiomes.add(value.biome()));
        assertTrue(distinctBiomes.size() > 1, "collapsed to a single biome (" + distinctBiomes + ") across " + assignment.size() + " faces");
    }

    /**
     * {@code selfAdjacencyLimit} regression coverage - see {@link
     * com.mineutze.worldgen.biomegraph.BiomeNode#selfAdjacencyLimit} for why this exists
     * (lets a biome span multiple ordinary hexes as one felt region, chosen over a
     * reactive geometry "claim a neighbor's territory" mechanism specifically because it
     * needs zero changes here or in WeightedVertexPlacement - only this class's legality
     * checks).
     */
    @Test
    void selfAdjacentPairIsLegalUpToItsOwnLimit() {
        // Two mutually-adjacent free faces, ONE biome in the whole graph (limit=1) - the
        // only way this solves at all is if both faces are allowed to be the same biome.
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(1), 1, Set.of(0)), Set.of());
        Set<Integer> free = Set.of(0, 1);
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 200, 300, 1)), List.of());

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 7L).orElseThrow();
        assertEquals(id("desert"), assignment.get(0).biome());
        assertEquals(id("desert"), assignment.get(1).biome());
    }

    @Test
    void selfAdjacencyLimitRejectsAThirdMutuallyAdjacentFace() {
        // A real mesh-vertex triple (0-1, 1-2, 0-2 all adjacent - exactly the shape a hex
        // mesh's vertex-degree-3 property guarantees exists). Same single-biome,
        // limit=1 graph as above: assigning "desert" to all 3 would give EACH face 2
        // desert-neighbors (its other two triple members), exceeding the cap of 1, and
        // there's no second biome to escape to - must report no legal assignment, not
        // silently over-cap.
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(
            Map.of(0, Set.of(1, 2), 1, Set.of(0, 2), 2, Set.of(0, 1)), Set.of()
        );
        Set<Integer> free = Set.of(0, 1, 2);
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 200, 300, 1)), List.of());

        assertEquals(Optional.empty(), TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 7L));
    }

    @Test
    void selfAdjacencyLimitDefaultsToZeroBanningItLikeToday() {
        // Same 2-face mesh as selfAdjacentPairIsLegalUpToItsOwnLimit, but the DEFAULT
        // (2-arg node(), selfAdjacencyLimit=0) - must still report no legal assignment,
        // proving the new mechanism changes nothing unless a biome explicitly opts in.
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(1), 1, Set.of(0)), Set.of());
        Set<Integer> free = Set.of(0, 1);
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 200, 300)), List.of());

        assertEquals(Optional.empty(), TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 7L));
    }

    /**
     * The actual behavior this whole change exists for: two self-adjacent free faces read
     * as ONE felt region split across two hexes, not two independently-full-target
     * instances of the same biome. Both faces must land on the SAME (even-split) width,
     * strictly below the range's max (proving neither drew an independent full-range
     * value), and the COMBINED felt value (recoverable via {@code width * sqrt(2)}, area
     * scaling as width-squared) must itself fall within the biome's declared range - the
     * per-face number no longer does, by design.
     */
    @Test
    void selfAdjacentPairSplitsOneCombinedTargetInsteadOfEachDrawingItsOwnFullWidth() {
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(1), 1, Set.of(0)), Set.of());
        Set<Integer> free = Set.of(0, 1);
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 900, 1500, 1)), List.of());

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 7L).orElseThrow();

        int widthA = assignment.get(0).widthBlocks();
        int widthB = assignment.get(1).widthBlocks();
        assertEquals(widthA, widthB, "expected an even split between the two paired faces");
        assertTrue(widthA < 1500, "expected a paired face's own width to be well below the biome's full max, got " + widthA);

        double recoveredCombinedWidth = widthA * Math.sqrt(2);
        assertTrue(recoveredCombinedWidth >= 898 && recoveredCombinedWidth <= 1502,
            "expected the recovered COMBINED width to fall within the biome's own declared range (900-1500, +-rounding), got " + recoveredCombinedWidth);
    }

    /**
     * Regression guard: self-adjacency-ENABLED biome with no same-biome FREE neighbor
     * must draw full-range width unaffected - pairing only applies between two FREE
     * faces; same-biome FIXED neighbor (already decided elsewhere) never triggers split.
     */
    @Test
    void singletonSelfAdjacencyEnabledFaceDrawsItsOwnFullWidthUnaffected() {
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(99)), Set.of());
        Set<Integer> free = Set.of(0);
        Map<Integer, BiomeId> fixed = Map.of(99, id("desert"));
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 900, 1500, 1)), List.of());

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment =
            TriangleIdentitySizeAssignment.solve(adjacency, free, fixed, graph, 7L).orElseThrow();

        assertEquals(id("desert"), assignment.get(0).biome());
        int width = assignment.get(0).widthBlocks();
        assertTrue(width >= 900 && width <= 1500, "expected the unpaired face's own width to stay within its full declared range, got " + width);
    }

    @Test
    void pairedWidthAssignmentIsDeterministic() {
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(1), 1, Set.of(0)), Set.of());
        Set<Integer> free = Set.of(0, 1);
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 900, 1500, 1)), List.of());

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> first = TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 7L).orElseThrow();
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> second = TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 7L).orElseThrow();
        assertEquals(first, second);
    }

    /**
     * The actual behavior the committed-width overload exists for: found exploring the
     * real live path (2026-09-11), a fresh face's width used to be picked with zero
     * visibility into what its real, already-committed neighbors locked in. A tight (well
     * below its biome's range midpoint) committed neighbor must bias the draw toward the
     * LOW end of the face's range, not plain uniform. Real, deterministic guarantee for
     * the SAME underlying hash draw (see {@link Bias}: {@code TOWARD_MIN}'s fraction is
     * provably {@code <=} plain uniform's, for the same draw), not just average-case - so
     * a direct {@code <=} comparison against the exact same (worldSeed, faceIndex, biome)
     * with no committed context is a strong, non-flaky assertion.
     */
    @Test
    void aCommittedTightNeighborBiasesTheDrawTowardTheLowEndOfItsOwnRange() {
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(99)), Set.of());
        Set<Integer> free = Set.of(0);
        Map<Integer, BiomeId> fixed = Map.of(99, id("desert"));
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 900, 1500, 1)), List.of());

        int widthWithoutCommittedContext = TriangleIdentitySizeAssignment.solve(
            adjacency, free, fixed, graph, 7L, Double.POSITIVE_INFINITY, Map.of()
        ).orElseThrow().get(0).widthBlocks();
        int widthWithTightCommittedNeighbor = TriangleIdentitySizeAssignment.solve(
            adjacency, free, fixed, graph, 7L, Double.POSITIVE_INFINITY, Map.of(99, 950) // well below the 1200 midpoint
        ).orElseThrow().get(0).widthBlocks();

        assertTrue(widthWithTightCommittedNeighbor <= widthWithoutCommittedContext,
            "expected a tight committed neighbor to bias the draw toward the low end: without=" + widthWithoutCommittedContext
                + ", with=" + widthWithTightCommittedNeighbor);
    }

    /** Regression guard: committed neighbor that ISN'T tight (well above range midpoint) must not change draw - bias only fires for case it was built for. */
    @Test
    void aCommittedNeighborThatIsNotTightDoesNotChangeTheDraw() {
        FaceAdjacency.Adjacency adjacency = new FaceAdjacency.Adjacency(Map.of(0, Set.of(99)), Set.of());
        Set<Integer> free = Set.of(0);
        Map<Integer, BiomeId> fixed = Map.of(99, id("desert"));
        BiomeGraph graph = new BiomeGraph(List.of(node("desert", 900, 1500, 1)), List.of());

        int widthWithoutCommittedContext = TriangleIdentitySizeAssignment.solve(
            adjacency, free, fixed, graph, 7L, Double.POSITIVE_INFINITY, Map.of()
        ).orElseThrow().get(0).widthBlocks();
        int widthWithRoomyCommittedNeighbor = TriangleIdentitySizeAssignment.solve(
            adjacency, free, fixed, graph, 7L, Double.POSITIVE_INFINITY, Map.of(99, 1400) // well above the 1200 midpoint
        ).orElseThrow().get(0).widthBlocks();

        assertEquals(widthWithoutCommittedContext, widthWithRoomyCommittedNeighbor);
    }

    /** Regression guard for overload chain: no committed context must produce BYTE-IDENTICAL result to pre-existing overloads, not just similar. */
    @Test
    void noCommittedContextBehavesExactlyAsBeforeThisChange() {
        FaceAdjacency.Adjacency adjacency = realAdjacency(350, 4000);
        Set<Integer> free = interiorFaceIndices(adjacency);
        BiomeGraph graph = threeBiomeChainGraph();

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> withNewOverload =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 21L, Double.POSITIVE_INFINITY, Map.of()).orElseThrow();
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> withOldOverload =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 21L).orElseThrow();

        assertEquals(withOldOverload, withNewOverload);
    }

    /**
     * Behavior mesh-aware overload exists for (2026-09-11, "compute achievable range
     * first, decide second"): candidate biome whose ENTIRE catalog range is
     * geometrically unreachable given already-committed neighbors must be
     * HARD-EXCLUDED, leaving only achievable candidate - structural fix, not another
     * tuning pass on solver machinery.
     */
    @Test
    void aCandidateBiomeFullyOutsideTheAchievableRangeIsExcludedFromTheDomain() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(300, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        Map<Integer, BiomeId> fixedFaceBiomes = new HashMap<>();
        Map<Integer, Integer> committedWidthBlocksByFace = new HashMap<>();
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            fixedFaceBiomes.put(neighbor, id("anchor"));
            committedWidthBlocksByFace.put(neighbor, 600);
        }
        Set<Integer> free = Set.of(center);

        BiomeGraph graph = new BiomeGraph(
            List.of(node("anchor", 400, 800), node("achievable", 400, 800), node("huge", 4000, 6000)),
            List.of(AdjacencyRule.allow(id("anchor"), id("achievable"), 10), AdjacencyRule.allow(id("anchor"), id("huge"), 10))
        );

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignment = TriangleIdentitySizeAssignment.solve(
            adjacency, free, fixedFaceBiomes, graph, 7L, Double.POSITIVE_INFINITY, committedWidthBlocksByFace, mesh, 300, 0.0
        ).orElseThrow();

        assertEquals(id("achievable"), assignment.get(center).biome(),
            "expected the geometrically-unreachable 'huge' candidate to be hard-excluded, leaving only 'achievable'");
    }

    /** Regression guard for overload chain: face with no committed neighbor context must behave BYTE-IDENTICAL whether through mesh-aware overload or not. */
    @Test
    void meshAwareOverloadBehavesExactlyAsBeforeWhenNoFaceHasAnyCommittedNeighbor() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(300, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        Set<Integer> free = interiorFaceIndices(adjacency);
        BiomeGraph graph = threeBiomeChainGraph();

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> withoutMesh =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 21L, Double.POSITIVE_INFINITY, Map.of()).orElseThrow();
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> withMesh =
            TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, 21L, Double.POSITIVE_INFINITY, Map.of(), mesh, 300, 0.0).orElseThrow();

        assertEquals(withoutMesh, withMesh);
    }

    /** Interior face whose 6 neighbors are ALSO non-hull-touching - matches {@code WeightedVertexPlacementTest}'s identically-named helper (deliberately not shared, same convention this codebase's tests use). */
    private static int aFaceWithSixFullyInteriorNeighbors(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency) {
        for (Map.Entry<Integer, Set<Integer>> entry : adjacency.neighborFaceIndices().entrySet()) {
            int faceIndex = entry.getKey();
            if (adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                continue;
            }
            Set<Integer> neighbors = entry.getValue();
            if (neighbors.size() == 6 && neighbors.stream().noneMatch(n -> adjacency.hullTouchingFaceIndices().contains(n))) {
                return faceIndex;
            }
        }
        throw new IllegalStateException("expected at least one fully-interior 6-neighbor face in a real-sized mesh");
    }

    /**
     * Non-trivial mesh for CSP-scale testing - real hex lattice production uses, not
     * second triangle-only generation algorithm kept solely for tests (was {@code
     * DelaunayTriangulation}/{@code IndexedTriangle}, deleted per user doubt about
     * maintaining separate mesh generator just for tests - see git history). Tests
     * don't need mesh-shape diversity (that's what real 50-seed starvation/dead-end
     * sweeps are for); deterministic hex lattice sized by {@code hexSizeBlocks}/{@code
     * span} is as "real-scale, not hand-built toy" as jittered triangulation was,
     * without second algorithm to maintain.
     */
    private static FaceAdjacency.Adjacency realAdjacency(int hexSizeBlocks, int span) {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(hexSizeBlocks, 0, 0, span, span);
        return FaceAdjacency.of(mesh.faces());
    }

    private static Set<Integer> interiorFaceIndices(FaceAdjacency.Adjacency adjacency) {
        Set<Integer> interior = new LinkedHashSet<>();
        for (int faceIndex : adjacency.neighborFaceIndices().keySet()) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                interior.add(faceIndex);
            }
        }
        return interior;
    }

    /**
     * Seam-level diagnostic between this class and {@link WeightedVertexPlacement}: does a
     * self-adjacent PATCH (a pair's own COMBINED realized area, since the pair together is
     * one felt region - see {@code CalibratedVertexPlacement.correctPairedFacesJointly}'s
     * own doc for why per-half comparison is the wrong metric here) realize its own
     * COMBINED target as faithfully as a singleton face realizes its own? Isolates the
     * mechanism named in {@code
     * ModBiomeGraphTest.overworldHasNoFaceOversizedOrUndersizedByMoreThanTenPercent}'s own
     * doc with a small, fast, single-seed mesh instead of that test's full {@code
     * ModBiomeGraph.OVERWORLD} sweep. Reads {@link SelfAdjacentPairing} directly
     * (package-private, same package) to know which faces paired, not re-derive it.
     */
    private static final int PAIRING_SIGNAL_TRIAL_COUNT = 10;
    private static final long PAIRING_SIGNAL_SEED_BASE = 2026L;

    @Test
    void pairedPatchesRealizeAsFaithfullyAsSingletonFaces() {
        // Uses REAL ModBiomeGraph.OVERWORLD catalog, not small synthetic graph -
        // measured while building this test: synthetic 3-biome graph (even one whose
        // widths span mesh's sizeBlocks pitch) has almost no same-biome adjacency to
        // grow into, so EVERY face - paired or not - comes out systematically
        // undersized, swamping pairing-specific signal. Real catalog's mix of biome
        // sizes/adjacency is what production bug (ModBiomeGraphTest's own
        // overworldHasNoFaceOversizedOrUndersizedByMoreThanTenPercent) was measured
        // against, so it's what real root-cause signal needs too - same
        // worldSeed/mesh-scale/trial convention as that test (only 5 catalog biomes
        // are self-adjacency-enabled at all, so single seed's single mesh often
        // pairs zero faces - multiple trials, same as that test's
        // SIZE_FIDELITY_TRIAL_COUNT, needed for real paired sample, not
        // smaller/faster substitute).
        double sizeBlocks = 325; // matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS
        int minEdgeLengthBlocks = 24; // matches MIN_EDGE_LENGTH_MACRO_CELLS(6) * macroCellSizeBlocks(4), same as ModBiomeGraphTest
        BiomeGraph graph = com.mineutze.worldgen.biomegraph.ModBiomeGraph.OVERWORLD;
        double regionAreaBlocks2 = 6000.0 * 6000.0;

        List<Double> pairedPatchDeviations = new ArrayList<>();
        List<Double> singletonDeviations = new ArrayList<>();
        for (int trial = 0; trial < PAIRING_SIGNAL_TRIAL_COUNT; trial++) {
            long worldSeed = PAIRING_SIGNAL_SEED_BASE + trial;
            HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(sizeBlocks, -3000, -3000, 3000, 3000);
            FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
            Set<Integer> free = interiorFaceIndices(adjacency);

            Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments =
                TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, worldSeed, regionAreaBlocks2).orElseThrow();
            Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, sizeBlocks, minEdgeLengthBlocks);

            Map<Integer, BiomeId> assignedBiomes = new HashMap<>();
            assignments.forEach((faceIndex, assignment) -> assignedBiomes.put(faceIndex, assignment.biome()));
            Map<Integer, Integer> pairPartnerByFace = SelfAdjacentPairing.pairedFaces(assignedBiomes, adjacency, graph);

            Set<Integer> accounted = new HashSet<>();
            for (int faceIndex : free) {
                if (!accounted.add(faceIndex)) {
                    continue; // already folded into its partner's own patch below
                }
                TriangleIdentitySizeAssignment.Assignment assignment = assignments.get(faceIndex);
                double realizedArea = shoelaceArea(mesh.faces().get(faceIndex).vertexIndices(), displaced);
                double targetArea = hexagonAreaForWidth(assignment.widthBlocks());
                Integer partner = pairPartnerByFace.get(faceIndex);
                if (partner != null && free.contains(partner)) {
                    accounted.add(partner);
                    TriangleIdentitySizeAssignment.Assignment partnerAssignment = assignments.get(partner);
                    double partnerRealizedArea = shoelaceArea(mesh.faces().get(partner).vertexIndices(), displaced);
                    double partnerTargetArea = hexagonAreaForWidth(partnerAssignment.widthBlocks());
                    double patchDeviation = Math.abs((realizedArea + partnerRealizedArea) / (targetArea + partnerTargetArea) - 1.0);
                    pairedPatchDeviations.add(patchDeviation);
                } else {
                    singletonDeviations.add(Math.abs(realizedArea / targetArea - 1.0));
                }
            }
        }

        assertTrue(pairedPatchDeviations.size() > 2, "expected a real number of paired patches actually checked across " + PAIRING_SIGNAL_TRIAL_COUNT + " trials, got " + pairedPatchDeviations.size());
        assertTrue(singletonDeviations.size() > 500, "expected a real number of singleton faces actually checked, got " + singletonDeviations.size());

        double meanPairedPatchDeviation = pairedPatchDeviations.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double meanSingletonDeviation = singletonDeviations.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        System.out.println("DEBUG patches=" + pairedPatchDeviations.size() + " singleton=" + singletonDeviations.size()
            + " meanPairedPatch=" + meanPairedPatchDeviation + " meanSingleton=" + meanSingletonDeviation
            + " raw=" + pairedPatchDeviations);

        assertTrue(meanPairedPatchDeviation <= meanSingletonDeviation * 1.5,
            "expected a paired patch's own COMBINED realized area to track its COMBINED target about as faithfully as a singleton "
                + "face tracks its own (within 1.5x) now that CalibratedVertexPlacement corrects a pair jointly - got mean paired-patch "
                + "deviation " + meanPairedPatchDeviation + " vs mean singleton deviation " + meanSingletonDeviation + " across "
                + pairedPatchDeviations.size() + " patches / " + singletonDeviations.size() + " singleton faces");
    }

    /** Same seam, real live path ({@code sequentiallyRealizedVertexPositions}, via {@code SequentialVertexPlacement}'s own joint-pair binary search) instead of the whole-mesh calibration path above - this is what the shipped mod actually runs. */
    @Test
    void pairedPatchesRealizeAsFaithfullyAsSingletonFacesOnTheLivePath() {
        double sizeBlocks = 325;
        int minEdgeLengthBlocks = 24;
        BiomeGraph graph = com.mineutze.worldgen.biomegraph.ModBiomeGraph.OVERWORLD;
        double regionAreaBlocks2 = 6000.0 * 6000.0;

        List<Double> pairedPatchDeviations = new ArrayList<>();
        List<Double> singletonDeviations = new ArrayList<>();
        for (int trial = 0; trial < PAIRING_SIGNAL_TRIAL_COUNT; trial++) {
            long worldSeed = PAIRING_SIGNAL_SEED_BASE + trial;
            HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(sizeBlocks, -3000, -3000, 3000, 3000);
            FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
            Set<Integer> free = interiorFaceIndices(adjacency);

            Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments =
                TriangleIdentitySizeAssignment.solve(adjacency, free, Map.of(), graph, worldSeed, regionAreaBlocks2).orElseThrow();
            Map<Integer, Point2D> displaced = WeightedVertexPlacement.sequentiallyRealizedVertexPositions(
                mesh, adjacency, assignments, sizeBlocks, minEdgeLengthBlocks, Map.of(), Map.of(), worldSeed
            );

            Set<Integer> accounted = new HashSet<>();
            for (int faceIndex : free) {
                if (!accounted.add(faceIndex)) {
                    continue;
                }
                TriangleIdentitySizeAssignment.Assignment assignment = assignments.get(faceIndex);
                double realizedArea = shoelaceArea(mesh.faces().get(faceIndex).vertexIndices(), displaced);
                double targetArea = hexagonAreaForWidth(assignment.widthBlocks());
                int partner = assignment.pairedWithFaceIndex();
                if (partner != TriangleIdentitySizeAssignment.Assignment.NOT_PAIRED && free.contains(partner)) {
                    accounted.add(partner);
                    TriangleIdentitySizeAssignment.Assignment partnerAssignment = assignments.get(partner);
                    double partnerRealizedArea = shoelaceArea(mesh.faces().get(partner).vertexIndices(), displaced);
                    double partnerTargetArea = hexagonAreaForWidth(partnerAssignment.widthBlocks());
                    pairedPatchDeviations.add(Math.abs((realizedArea + partnerRealizedArea) / (targetArea + partnerTargetArea) - 1.0));
                } else {
                    singletonDeviations.add(Math.abs(realizedArea / targetArea - 1.0));
                }
            }
        }

        assertTrue(pairedPatchDeviations.size() > 2, "expected a real number of paired patches actually checked across " + PAIRING_SIGNAL_TRIAL_COUNT + " trials, got " + pairedPatchDeviations.size());
        assertTrue(singletonDeviations.size() > 500, "expected a real number of singleton faces actually checked, got " + singletonDeviations.size());

        double meanPairedPatchDeviation = pairedPatchDeviations.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double meanSingletonDeviation = singletonDeviations.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        System.out.println("DEBUG live-path patches=" + pairedPatchDeviations.size() + " singleton=" + singletonDeviations.size()
            + " meanPairedPatch=" + meanPairedPatchDeviation + " meanSingleton=" + meanSingletonDeviation
            + " raw=" + pairedPatchDeviations);

        assertTrue(meanPairedPatchDeviation <= meanSingletonDeviation * 1.5,
            "expected a paired patch's own COMBINED realized area, on the REAL live path, to track its COMBINED target about as "
                + "faithfully as a singleton face tracks its own (within 1.5x) now that SequentialVertexPlacement resolves a pair "
                + "jointly - got mean paired-patch deviation " + meanPairedPatchDeviation + " vs mean singleton deviation "
                + meanSingletonDeviation + " across " + pairedPatchDeviations.size() + " patches / " + singletonDeviations.size()
                + " singleton faces");
    }

    private static double shoelaceArea(List<Integer> vertexIndices, Map<Integer, Point2D> positions) {
        double sum = 0;
        int n = vertexIndices.size();
        for (int i = 0; i < n; i++) {
            Point2D a = positions.get(vertexIndices.get(i));
            Point2D b = positions.get(vertexIndices.get((i + 1) % n));
            sum += a.x() * b.z() - b.x() * a.z();
        }
        return Math.abs(sum) / 2.0;
    }

    /** Same convention {@code WeightedVertexPlacement.powerWeight}/{@code targetAreaBlocks2} use: width is regular hexagon's corner-to-corner span, circumradius = width/2. */
    private static double hexagonAreaForWidth(int widthBlocks) {
        double circumradius = widthBlocks / 2.0;
        return (3.0 * Math.sqrt(3.0) / 2.0) * circumradius * circumradius;
    }

    // Bare 3-node chain (no desert-plains edge) leaves plenty interior faces with no
    // legal candidate now self-adjacency banned: face surrounded entirely by other free
    // faces (no fixed anchor) needs SOME biome legal against whatever neighbors settle
    // on, and 2-edge chain with no self-adjacency escape hatch runs out of options
    // across real mesh. Closed into 3-clique (every pair mutually legal), same "add
    // missing edge" fix already established for
    // TriangleFieldGeometryRealizationTest.fourBiomeGraph's hub.
    private static BiomeGraph threeBiomeChainGraph() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10),
                AdjacencyRule.allow(id("desert"), id("plains"), 10)
            )
        );
    }
}
