package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.HexGrid;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.NodeEdge;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangleFieldGeometryRealizationTest {

    // 1024 blocks/side - large enough to actually fit multiple full-target-sized patches
    // (this graph's biggest range tops out at 300), since SeamGrowthRasterizer's quota-first
    // growth pushes each patch toward its own declared target cell count. A smaller grid
    // (256 blocks/side) genuinely can't hold more than about one correctly-sized patch,
    // which made producesRealDiversityNotASingleBiome fail against a fixture that small -
    // not a bug, just an unrealistic fixture size for what the algorithm actually does.
    // Still well under MacroGridConfig.DEFAULT (2048 blocks/side, ~262k cells) for test speed.
    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(4, 256);

    private static BiomeGraph fourBiomeGraph() {
        // Bare 4-cycle (no diagonals) has a genuine dead end: {desert, savanna, plains,
        // badlands} converging simultaneously has no legal candidate (savanna-badlands is
        // never allowed) - the geometry-realization rasterizer of the time correctly threw
        // on this before the desert-plains diagonal below was added, since a real Moore-neighbor convergence
        // can plausibly reach all 4 of this graph's only 4 biomes at once. Adding ONE
        // diagonal (not both) makes plains a local hub solvable for every subset without
        // making the whole graph a trivial always-legal clique - savanna-badlands stays
        // illegal, so a broken rasterizer could still be caught producing that specific
        // illegal pair.
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200), node("badlands", 150, 250)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10),
                AdjacencyRule.allow(id("plains"), id("badlands"), 10),
                AdjacencyRule.allow(id("desert"), id("badlands"), 10),
                AdjacencyRule.allow(id("desert"), id("plains"), 10)
            )
        );
    }

    private static RegionSolveInput input(long worldSeed) {
        return new RegionSolveInput(fourBiomeGraph(), SMALL_GRID, new RegionPos(0, 0), Map.of(), worldSeed);
    }

    /**
     * A hub (desert) legal against everything else, PLUS one extra edge (savanna-plains)
     * closing a triangle among the non-hub biomes - deliberately built for {@link
     * #twoAttemptRecoverySucceedsWhereASingleTierWouldHaveDeadEnded}: a ring alternating
     * {@code badlands-desert-badlands-desert-badlands-desert} has NO legal center (only
     * desert is legal against badlands, but desert can't be its own neighbor - see that
     * test's own doc for the full derivation), while a ring alternating {@code
     * savanna-plains-savanna-plains-savanna-plains} DOES (desert is legal against both) -
     * the exact "recoverable if freed, dead if frozen" shape the two-attempt strategy
     * exists for.
     */
    private static BiomeGraph hubPlusTriangleGraph() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200), node("badlands", 150, 250)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("desert"), id("plains"), 10),
                AdjacencyRule.allow(id("desert"), id("badlands"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10)
            )
        );
    }

    /** The bare 4-cycle from {@link #fourBiomeGraph}'s own doc, minus the diagonal that makes it solvable - kept only for {@link #genuineDeadEndFailsLoud}. */
    private static BiomeGraph bareFourCycleGraphWithNoHub() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200), node("badlands", 150, 250)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10),
                AdjacencyRule.allow(id("plains"), id("badlands"), 10),
                AdjacencyRule.allow(id("desert"), id("badlands"), 10)
            )
        );
    }

    @Test
    void everyCellInTheRegionGetsAssigned() {
        RegionSolveResult result = TriangleFieldGeometryRealization.realize(input(1L));
        List<MacroCellPos> cells = SMALL_GRID.cellsInRegion(new RegionPos(0, 0), 1L);
        assertEquals(cells.size(), result.assignment().size());
        for (MacroCellPos cell : cells) {
            assertTrue(result.assignment().containsKey(cell), "cell " + cell + " has no assignment");
        }
    }

    @Test
    void sameInputProducesTheSameResult() {
        RegionSolveResult first = TriangleFieldGeometryRealization.realize(input(7L));
        RegionSolveResult second = TriangleFieldGeometryRealization.realize(input(7L));
        assertEquals(first.assignment(), second.assignment());
    }

    @Test
    void producesRealDiversityNotASingleBiome() {
        RegionSolveResult result = TriangleFieldGeometryRealization.realize(input(2026L));
        Set<BiomeId> distinct = new HashSet<>(result.assignment().values());
        assertTrue(distinct.size() > 1, "collapsed to a single biome: " + distinct);
    }

    /**
     * Was a rate threshold ({@code < 0.05}) before the geometry-realization rasterizer
     * legality-constrained its own output - that framing itself implied tolerance for
     * illegal pairs. Now a hard zero, Moore (8-way) neighbors included, matching
     * {@link SeamGrowthRasterizer}'s own actual guarantee.
     */
    @Test
    void producesZeroIllegalAdjacencies() {
        RegionSolveResult result = TriangleFieldGeometryRealization.realize(input(2026L));
        Map<MacroCellPos, BiomeId> assignment = result.assignment();
        BiomeGraph graph = fourBiomeGraph();

        int illegal = 0;
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            for (MacroCellPos neighbor : mooreNeighbors(entry.getKey())) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !entry.getValue().equals(neighborBiome) && !graph.isAllowed(entry.getValue(), neighborBiome)) {
                    illegal++;
                }
            }
        }
        assertEquals(0, illegal, "found an illegal Moore-adjacent biome pair in the final rasterized output");
    }

    private static List<MacroCellPos> mooreNeighbors(MacroCellPos cell) {
        List<MacroCellPos> neighbors = new java.util.ArrayList<>(cell.orthogonalNeighbors());
        neighbors.addAll(cell.diagonalNeighbors());
        return neighbors;
    }

    /**
     * The genuine dead end {@link #fourBiomeGraph}'s own doc describes, reproduced directly:
     * a bare 4-cycle graph (no diagonal, unlike the real fixture) has NO biome legal against
     * all of {desert, savanna, plains, badlands} converging on a single hex face's own
     * neighbor set (savanna-badlands is never allowed, and self-adjacency isn't a free pass
     * either). Unlike the old triangle+growth path, where this could only be caught live
     * during rasterization, the hex mesh's vertex-degree-3 completeness means legality is
     * fully decided at CSP time - {@link TriangleIdentitySizeAssignment#solve} itself must
     * fail loud here (via {@link TriangleFieldGeometryRealization#realize}'s own
     * {@code orElseThrow}), never silently place an illegal biome.
     */
    @Test
    void genuineDeadEndFailsLoud() {
        RegionSolveInput input = new RegionSolveInput(bareFourCycleGraphWithNoHub(), SMALL_GRID, new RegionPos(0, 0), Map.of(), 2026L);
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> TriangleFieldGeometryRealization.realize(input)
        );
        assertTrue(thrown.getMessage().contains("no legal identity assignment exists"), "expected a diagnostic naming the CSP's own failure, got: " + thrown.getMessage());
    }

    // --- organic query-centered cluster growth (no fixed grid at all) ---
    // Supersedes an earlier window-based store-aware `realize` overload (since deleted -
    // it had the exact same "foreign uncommitted face" seam bug the cluster design below
    // fixes at its actual root; see doc/decisions/2026-08-29-remove-region-stitching.md
    // and this whole redesign's own plan doc for the full story).

    @Test
    void fastPathFindsNothingBeforeAnythingIsSolved() {
        assertTrue(TriangleFieldGeometryRealization.tryFastPathLookup(1L, 0.0, 0.0, new DecidedFaceStore(), new DecidedVertexStore()).isEmpty());
    }

    @Test
    void fastPathFindsWhatClusterGrowthJustCommitted() {
        DecidedFaceStore faceStore = new DecidedFaceStore();
        DecidedVertexStore vertexStore = new DecidedVertexStore();
        BiomeGraph graph = fourBiomeGraph();

        BiomeId solved = TriangleFieldGeometryRealization.realizeCluster(graph, SMALL_GRID, 1L, 0.0, 0.0, faceStore, vertexStore);

        Optional<BiomeId> fastPathResult = TriangleFieldGeometryRealization.tryFastPathLookup(1L, 0.0, 0.0, faceStore, vertexStore);
        assertEquals(Optional.of(solved), fastPathResult);
    }

    /**
     * The direct regression test for the whole reason this redesign moved off fixed
     * windows: two nearby query points, solved through the SAME pair of global stores in
     * EITHER order, must land on the identical biome for each point - no window, no
     * ownership, no "whoever solves first" asymmetry left anywhere in the mechanism.
     */
    @Test
    void clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst() {
        BiomeGraph graph = fourBiomeGraph();
        long worldSeed = 2026L;
        double ax = 0.0;
        double az = 0.0;
        double bx = 900.0;
        double bz = 0.0;

        DecidedFaceStore forwardFaces = new DecidedFaceStore();
        DecidedVertexStore forwardVertices = new DecidedVertexStore();
        BiomeId aSolvedFirst = TriangleFieldGeometryRealization.realizeCluster(graph, SMALL_GRID, worldSeed, ax, az, forwardFaces, forwardVertices);
        BiomeId bSolvedSecond = TriangleFieldGeometryRealization.realizeCluster(graph, SMALL_GRID, worldSeed, bx, bz, forwardFaces, forwardVertices);

        DecidedFaceStore reverseFaces = new DecidedFaceStore();
        DecidedVertexStore reverseVertices = new DecidedVertexStore();
        BiomeId bSolvedFirst = TriangleFieldGeometryRealization.realizeCluster(graph, SMALL_GRID, worldSeed, bx, bz, reverseFaces, reverseVertices);
        BiomeId aSolvedSecond = TriangleFieldGeometryRealization.realizeCluster(graph, SMALL_GRID, worldSeed, ax, az, reverseFaces, reverseVertices);

        assertEquals(aSolvedFirst, aSolvedSecond, "point A's own biome depended on solve order");
        assertEquals(bSolvedSecond, bSolvedFirst, "point B's own biome depended on solve order");
    }

    /**
     * The direct performance regression test for the redesign's own stated goal: a point
     * already covered by a previous cluster solve must be answered by the fast path alone
     * (real point-in-polygon test against already-committed geometry) — deciding ZERO new
     * faces, not re-triggering any solve at all. This is what makes repeat/nearby queries
     * in already-explored territory cheap, unlike the old fixed-size-window design where
     * even a cache hit still meant "this exact grid square was solved before."
     */
    @Test
    void queryingAnAlreadyCoveredPointCostsNoNewFaces() {
        DecidedFaceStore faceStore = new DecidedFaceStore();
        DecidedVertexStore vertexStore = new DecidedVertexStore();
        BiomeGraph graph = fourBiomeGraph();
        long worldSeed = 2026L;

        TriangleFieldGeometryRealization.realizeCluster(graph, SMALL_GRID, worldSeed, 0.0, 0.0, faceStore, vertexStore);
        int decidedAfterFirst = faceStore.permanentCount() + faceStore.tentativeCount();
        assertTrue(decidedAfterFirst > 0, "expected the first cluster to have decided at least one face");

        // Comfortably within the first cluster's own footprint (well under its own
        // CLUSTER_GROWTH_CAP_RADIUS_BLOCKS), so it must already have a value (tentative
        // or permanent). tryFastPathLookup is read-only, so this must not change either count.
        Optional<BiomeId> alreadyCovered = TriangleFieldGeometryRealization.tryFastPathLookup(worldSeed, 500.0, 0.0, faceStore, vertexStore);
        assertTrue(alreadyCovered.isPresent(), "expected a point well inside the first cluster's own footprint to already have a value");
        assertEquals(decidedAfterFirst, faceStore.permanentCount() + faceStore.tentativeCount(), "a fast-path hit must never decide any new faces");
    }

    @Test
    void realizeClusterFailsLoudOnAGenuineDeadEnd() {
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
            TriangleFieldGeometryRealization.realizeCluster(bareFourCycleGraphWithNoHub(), SMALL_GRID, 2026L, 0.0, 0.0, new DecidedFaceStore(), new DecidedVertexStore())
        );
        assertTrue(thrown.getMessage().contains("no legal identity assignment exists"), "expected a diagnostic naming the CSP's own failure, got: " + thrown.getMessage());
    }

    // Matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually
    // (same convention ModBiomeGraphTest's own solveAndRasterizeOverworld already uses),
    // since axial identity between this test's own mesh and realizeCluster's internal one
    // only lines up if both use the identical hex size.
    private static final double HEX_SIZE_BLOCKS = 325;

    /**
     * The direct regression test for the two-attempt recovery strategy itself (see
     * doc/decisions on this whole redesign's own plan doc, and the chat investigation this
     * session that led to it): a center face surrounded by 6 already-committed neighbors
     * alternating {@code badlands, desert, badlands, desert, badlands, desert} has NO
     * legal center under {@link #hubPlusTriangleGraph} - only desert is legal against
     * badlands, but desert can't be its own neighbor (self-adjacency banned), and desert
     * IS one of the ring members. If those 6 neighbors are PERMANENT (immovable, matching
     * the OLD single-tier design), {@code realizeCluster} must fail loud - confirmed here
     * as a control, proving this scenario is a REAL dead end, not a strawman. If instead
     * those same 6 neighbors are only TENTATIVE, {@code realizeCluster} must SUCCEED -
     * attempt 2 pulls them back in as free faces and the CSP finds a real joint solution
     * (e.g. center=desert, ring alternating savanna/plains instead, both legal against
     * desert and against each other).
     */
    @Test
    void twoAttemptRecoverySucceedsWhereASingleTierWouldHaveDeadEnded() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(HEX_SIZE_BLOCKS, -2000, -2000, 2000, 2000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int centerFaceIndex = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);
        List<Integer> ring = neighborsInRingOrder(mesh, adjacency, centerFaceIndex);
        assertEquals(6, ring.size());

        BiomeId badlands = id("badlands");
        BiomeId desert = id("desert");
        Map<HexGrid.AxialPos, DecidedFaceStore.DecidedFace> deadEndRing = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ring.size(); i++) {
            BiomeId biome = i % 2 == 0 ? badlands : desert;
            int width = biome.equals(badlands) ? 200 : 250;
            deadEndRing.put(mesh.faces().get(ring.get(i)).axialPos(), new DecidedFaceStore.DecidedFace(biome, width));
        }

        Point2D centerPoint = centroidOf(mesh, centerFaceIndex);

        // Control: the SAME ring, but PERMANENT (immovable) - must genuinely fail.
        DecidedFaceStore permanentFaceStore = new DecidedFaceStore();
        permanentFaceStore.commitPermanent(deadEndRing);
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
            TriangleFieldGeometryRealization.realizeCluster(
                hubPlusTriangleGraph(), SMALL_GRID, 2026L, centerPoint.x(), centerPoint.z(), permanentFaceStore, new DecidedVertexStore()
            )
        );
        assertTrue(thrown.getMessage().contains("no legal identity assignment exists"), "expected the all-permanent control to genuinely dead-end, got: " + thrown.getMessage());

        // The real case: the SAME ring, but only TENTATIVE - must succeed via attempt 2.
        DecidedFaceStore tentativeFaceStore = new DecidedFaceStore();
        tentativeFaceStore.commitTentative(deadEndRing);
        BiomeId resolved = TriangleFieldGeometryRealization.realizeCluster(
            hubPlusTriangleGraph(), SMALL_GRID, 2026L, centerPoint.x(), centerPoint.z(), tentativeFaceStore, new DecidedVertexStore()
        );
        assertTrue(resolved != null, "expected the tentative case to recover via attempt 2, not throw");
    }

    /** Ring-ordered (not just set-ordered) neighbors of a face - position {@code i} shares the face's own edge {@code (vertexIndices[i], vertexIndices[i+1])}. */
    private static List<Integer> neighborsInRingOrder(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, int faceIndex) {
        List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
        int cornerCount = vertexIndices.size();
        List<Integer> ring = new ArrayList<>();
        for (int i = 0; i < cornerCount; i++) {
            NodeEdge edge = NodeEdge.of(vertexIndices.get(i), vertexIndices.get((i + 1) % cornerCount));
            Integer other = null;
            for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(faceIndex, Set.of())) {
                if (mesh.faces().get(neighbor).edges().contains(edge)) {
                    other = neighbor;
                    break;
                }
            }
            ring.add(other);
        }
        return ring;
    }

    private static int aFaceWithSixFullyInteriorNeighbors(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency) {
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

    private static Point2D centroidOf(HexMeshBuilder.HexMesh mesh, int faceIndex) {
        double sumX = 0;
        double sumZ = 0;
        for (int vertexIndex : mesh.faces().get(faceIndex).vertexIndices()) {
            Point2D vertex = mesh.vertices().get(vertexIndex);
            sumX += vertex.x();
            sumZ += vertex.z();
        }
        int cornerCount = mesh.faces().get(faceIndex).vertexIndices().size();
        return new Point2D(sumX / cornerCount, sumZ / cornerCount);
    }
}
