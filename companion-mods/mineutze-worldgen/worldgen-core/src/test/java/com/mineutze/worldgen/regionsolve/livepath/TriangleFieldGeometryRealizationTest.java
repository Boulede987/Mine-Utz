package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexMeshBuilder;

import com.mineutze.worldgen.regionsolve.topology.mesh.NodeEdge;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.regionsolve.RegionPos;

import com.mineutze.worldgen.regionsolve.RegionSolveResult;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.regionsolve.RegionSolveInput;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
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

    // 1024 blocks/side - large enough to fit multiple full-target-sized patches (this
    // graph's biggest range tops out at 300), since SeamGrowthRasterizer's quota-first
    // growth pushes each patch toward its declared target cell count. Smaller grid (256
    // blocks/side) genuinely can't hold more than about one correctly-sized patch, which
    // made producesRealDiversityNotASingleBiome fail against fixture that small - not a
    // bug, just unrealistic fixture size for what algorithm actually does. Still well
    // under MacroGridConfig.DEFAULT (2048 blocks/side, ~262k cells) for test speed.
    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(4, 256);

    private static BiomeGraph fourBiomeGraph() {
        // Bare 4-cycle (no diagonals) has genuine dead end: {desert, savanna, plains,
        // badlands} converging simultaneously has no legal candidate (savanna-badlands
        // never allowed) - geometry-realization rasterizer of the time correctly threw on
        // this before desert-plains diagonal below was added, since real Moore-neighbor
        // convergence can plausibly reach all 4 of this graph's only 4 biomes at once.
        // Adding ONE diagonal (not both) makes plains a local hub solvable for every
        // subset without making whole graph a trivial always-legal clique -
        // savanna-badlands stays illegal, so broken rasterizer could still be caught
        // producing that specific illegal pair.
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
     * Hub (desert) legal against everything else, PLUS one extra edge (savanna-plains)
     * closing triangle among non-hub biomes - deliberately built for {@link
     * #twoAttemptRecoverySucceedsWhereASingleTierWouldHaveDeadEnded}: ring alternating
     * {@code badlands-desert-badlands-desert-badlands-desert} has NO legal center (only
     * desert legal against badlands, but desert can't be its own neighbor - see that
     * test's doc for full derivation), while ring alternating {@code
     * savanna-plains-savanna-plains-savanna-plains} DOES (desert legal against both) -
     * exact "recoverable if freed, dead if frozen" shape two-attempt strategy exists for.
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

    /**
     * Bare 4-cycle from {@link #fourBiomeGraph}'s doc, minus diagonal that makes it
     * solvable - kept only for {@link #genuineDeadEndFailsLoud}/{@link
     * #realizeClusterFailsLoudOnAGenuineDeadEnd}. Explicit {@code selfAdjacencyLimit=0} on
     * every node (2026-09-14, real regression found and fixed): {@link BiomeNode}'s own
     * default changed from banned to uncapped this session, and same-biome-next-to-itself
     * is ALWAYS trivially legal once allowed - without this, every face could simply
     * repeat its own biome and escape what's supposed to be a genuine, inescapable dead
     * end, silently defeating this fixture's entire premise.
     */
    private static BiomeGraph bareFourCycleGraphWithNoHub() {
        return new BiomeGraph(
            List.of(
                new BiomeNode(id("desert"), new BiomeSizeRange(200, 300), 0), new BiomeNode(id("savanna"), new BiomeSizeRange(200, 300), 0),
                new BiomeNode(id("plains"), new BiomeSizeRange(100, 200), 0), new BiomeNode(id("badlands"), new BiomeSizeRange(150, 250), 0)
            ),
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
     * Was rate threshold ({@code < 0.05}) before geometry-realization rasterizer
     * legality-constrained its output - that framing itself implied tolerance for illegal
     * pairs. Now hard zero, Moore (8-way) neighbors included, matching {@code
     * SeamGrowthRasterizer}'s (deleted) actual guarantee - {@link
     * EdgeDisplacementRasterizer} carries same guarantee in current pipeline.
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
     * Genuine dead end {@link #fourBiomeGraph}'s doc describes, reproduced directly: bare
     * 4-cycle graph (no diagonal, unlike real fixture) has NO biome legal against all of
     * {desert, savanna, plains, badlands} converging on single hex face's neighbor set
     * (savanna-badlands never allowed, self-adjacency isn't free pass either). Unlike old
     * triangle+growth path, where this could only be caught live during rasterization, hex
     * mesh's vertex-degree-3 completeness means legality fully decided at CSP time - {@link
     * TriangleIdentitySizeAssignment#solve} itself must fail loud here (via {@link
     * TriangleFieldGeometryRealization#realize}'s {@code orElseThrow}), never silently
     * place illegal biome.
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
    // Supersedes earlier window-based store-aware `realize` overload (since deleted - had
    // exact same "foreign uncommitted face" seam bug cluster design below fixes at its
    // actual root; see doc/decisions/2026-08-29-remove-region-stitching.md and whole
    // redesign's plan doc for full story).

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
     * Direct regression test for whole reason this redesign moved off fixed windows: two
     * nearby query points, solved through SAME pair of global stores in EITHER order, must
     * land on identical biome for each point - no window, no ownership, no "whoever solves
     * first" asymmetry left anywhere in mechanism.
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
     * Single most important property of "tentative geometry, revisable like tentative
     * identity already is" (2026-09-11, "Option 2"): allowing tentative face's geometry
     * to be recomputed as more real context arrives must NOT let eventual PERMANENT
     * result depend on exploration order - same seed still means same world, regardless
     * of which order revealed shared neighborhood first. Not a new risk this change
     * invents (identity's tentative tier already allows revision under attempt 2), but
     * widens surface area, so gets its own, stronger test - three points close enough
     * together that their cluster growth genuinely overlaps (not wider-spaced, more
     * independent pair {@link #clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst}
     * already covers), queried forward and reverse through two separate, independent
     * pairs of stores.
     */
    @Test
    void permanentResultsAgreeRegardlessOfExplorationOrderEvenWithTentativeGeometryRevision() {
        BiomeGraph graph = fourBiomeGraph();
        long worldSeed = 2031L;
        double[][] points = {{0.0, 0.0}, {280.0, 0.0}, {140.0, 240.0}};

        DecidedFaceStore forwardFaces = new DecidedFaceStore();
        DecidedVertexStore forwardVertices = new DecidedVertexStore();
        List<BiomeId> forwardResults = new ArrayList<>();
        for (double[] point : points) {
            forwardResults.add(TriangleFieldGeometryRealization.realizeCluster(graph, SMALL_GRID, worldSeed, point[0], point[1], forwardFaces, forwardVertices));
        }

        DecidedFaceStore reverseFaces = new DecidedFaceStore();
        DecidedVertexStore reverseVertices = new DecidedVertexStore();
        List<BiomeId> reverseResults = new ArrayList<>();
        for (int i = points.length - 1; i >= 0; i--) {
            reverseResults.add(0, TriangleFieldGeometryRealization.realizeCluster(graph, SMALL_GRID, worldSeed, points[i][0], points[i][1], reverseFaces, reverseVertices));
        }

        assertEquals(forwardResults, reverseResults, "expected every point's own final biome to be identical regardless of exploration order");
    }

    /**
     * Direct performance regression test for redesign's stated goal: point already
     * covered by previous cluster solve must be answered by fast path alone (real
     * point-in-polygon test against already-committed geometry) - deciding ZERO new
     * faces, not re-triggering any solve at all. Makes repeat/nearby queries in
     * already-explored territory cheap, unlike old fixed-size-window design where even a
     * cache hit still meant "this exact grid square was solved before."
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

        // Comfortably within first cluster's footprint (well under its
        // CLUSTER_GROWTH_CAP_RADIUS_BLOCKS), so must already have a value (tentative or
        // permanent). tryFastPathLookup is read-only, so must not change either count.
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
    // (same convention ModBiomeGraphTest's solveAndRasterizeOverworld already uses),
    // since axial identity between this test's mesh and realizeCluster's internal one
    // only lines up if both use identical hex size.
    private static final double HEX_SIZE_BLOCKS = 325;

    /**
     * Direct regression test for two-attempt recovery strategy itself (see doc/decisions
     * on whole redesign's plan doc, and chat investigation this session that led to it):
     * center face surrounded by 6 already-committed neighbors alternating {@code
     * badlands, desert, badlands, desert, badlands, desert} has NO legal center under
     * {@link #hubPlusTriangleGraph} - only desert legal against badlands, but desert
     * can't be its own neighbor (self-adjacency banned), and desert IS one of ring
     * members. If those 6 neighbors are PERMANENT (immovable, matching OLD single-tier
     * design), {@code realizeCluster} must fail loud - confirmed here as control, proving
     * this scenario is REAL dead end, not strawman. If instead those same 6 neighbors are
     * only TENTATIVE, {@code realizeCluster} must SUCCEED - attempt 2 pulls them back in
     * as free faces and CSP finds real joint solution (e.g. center=desert, ring
     * alternating savanna/plains instead, both legal against desert and each other).
     */
    @Test
    void twoAttemptRecoverySucceedsWhereASingleTierWouldHaveDeadEnded() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(HEX_SIZE_BLOCKS, -2000, -2000, 2000, 2000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
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

        // Control: SAME ring, but PERMANENT (immovable) - must genuinely fail.
        DecidedFaceStore permanentFaceStore = new DecidedFaceStore();
        permanentFaceStore.commitPermanent(deadEndRing);
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
            TriangleFieldGeometryRealization.realizeCluster(
                hubPlusTriangleGraph(), SMALL_GRID, 2026L, centerPoint.x(), centerPoint.z(), permanentFaceStore, new DecidedVertexStore()
            )
        );
        assertTrue(thrown.getMessage().contains("no legal identity assignment exists"), "expected the all-permanent control to genuinely dead-end, got: " + thrown.getMessage());

        // Real case: SAME ring, but only TENTATIVE - must succeed via attempt 2.
        DecidedFaceStore tentativeFaceStore = new DecidedFaceStore();
        tentativeFaceStore.commitTentative(deadEndRing);
        BiomeId resolved = TriangleFieldGeometryRealization.realizeCluster(
            hubPlusTriangleGraph(), SMALL_GRID, 2026L, centerPoint.x(), centerPoint.z(), tentativeFaceStore, new DecidedVertexStore()
        );
        assertTrue(resolved != null, "expected the tentative case to recover via attempt 2, not throw");
    }

    /** Ring-ordered (not just set-ordered) neighbors of a face - position {@code i} shares face's edge {@code (vertexIndices[i], vertexIndices[i+1])}. */
    private static List<Integer> neighborsInRingOrder(HexMeshBuilder.HexMesh mesh, FaceAdjacency.Adjacency adjacency, int faceIndex) {
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
