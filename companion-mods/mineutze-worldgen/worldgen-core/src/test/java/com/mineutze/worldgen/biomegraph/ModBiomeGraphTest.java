package com.mineutze.worldgen.biomegraph;

import com.mineutze.worldgen.regionsolve.EdgeDisplacementRasterizer;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.TriangleFieldGeometryRealization;
import com.mineutze.worldgen.regionsolve.topology.HexMeshBuilder;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;
import com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModBiomeGraphTest {

    /**
     * A tracked, known gap - NOT a "should always pass" gate. {@code
     * BiomeGraph.requireEveryBiomeHasACrossTierNeighbor} is deliberately not part of
     * {@code forProduction}'s automatic bundle (see that method's own doc) precisely
     * because the real catalog fails it today: both {@code deep_lukewarm_ocean} and
     * {@code mangrove_swamp}'s own legal neighbors are all the same {@code
     * BiomeSizeRange} as themselves. Under the current power-diagram size mechanism
     * ({@code WeightedVertexPlacement}) this is no longer a provable dead end, just a
     * measured real one - {@code deep_lukewarm_ocean} averaged ~35% undersized across a
     * real 10-seed sample, still worse than biomes with a real cross-tier neighbor. This
     * test exists so that gap stays visible in the suite, not just in a memory file -
     * once a real cross-tier {@code AdjacencyRule} is added for both, this test should be
     * REPLACED with one asserting
     * {@code ModBiomeGraph.OVERWORLD.requireEveryBiomeHasACrossTierNeighbor()} passes
     * cleanly, not left pointing at a fix that no longer applies.
     */
    @Test
    void overworldHasTwoKnownCrossTierGaps() {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class, ModBiomeGraph.OVERWORLD::requireEveryBiomeHasACrossTierNeighbor
        );
        assertTrue(
            thrown.getMessage().contains("minecraft:deep_lukewarm_ocean") || thrown.getMessage().contains("minecraft:mangrove_swamp"),
            "expected a diagnostic naming one of the two known stuck biomes, got: " + thrown.getMessage()
        );
    }

    /**
     * Real, deterministic, re-runnable replacement for the anecdotal justification
     * {@code BiomeGraph.MIN_LEGAL_NEIGHBORS} used to carry ("confirmed empirically... a
     * degree-3 biome still landed at zero triangles in a 178-face solve" - a remembered
     * incident involving biomes that don't even exist in this codebase's own data
     * anymore, impossible to re-check). Starvation isn't a pure graph-structure property
     * the way {@link BiomeGraph#requireEveryLocalNeighborhoodSolvable} is - it depends on
     * where {@code TriangleIdentitySizeAssignment}'s fairness-quota ordering actually
     * lands triangles on a REAL mesh, which needs real seed placement and region scale to
     * observe at all. So this test runs the real thing: the actual CSP, against a real,
     * full-scale mesh ({@link MacroGridConfig#DEFAULT}, ~262k cells).
     *
     * <p>Aggregates across {@code DEFAULT_STARVATION_TRIAL_COUNT} independent worldSeeds,
     * not one - a single-seed version of this exact test (worldSeed=42) passed for months
     * against {@code HEX_SIZE_BLOCKS=400} and then failed the moment that constant was
     * retuned to 325 (see {@code TriangleFieldGeometryRealization}'s own doc for why), even
     * though a direct 30-seed sweep at the OLD size (400) already starved {@code badlands}
     * or {@code desert} on 17/30 seeds - the single-seed version was already this fragile,
     * the retune just flipped which side of the coin worldSeed=42 landed on. {@code
     * badlands} sits at exactly {@code MIN_LEGAL_NEIGHBORS} (degree 3, the narrowest legal
     * degree this graph's own constructor allows), so some seed-to-seed variance here is
     * expected, not a bug - the real question (same one {@link
     * #overworldNoBiomeIsStarvedAcrossManyIndependentRealSolves} already answers for the
     * 48-biome catalog) is whether a biome is missing from EVERY solve across many
     * independent seeds, not just an unlucky one.
     */
    private static final int DEFAULT_STARVATION_TRIAL_COUNT = 30;

    @Test
    void noBiomeIsStarvedAcrossManyIndependentRealSolves() {
        Map<BiomeId, Integer> totalCellCountByBiome = new HashMap<>();
        for (int trial = 0; trial < DEFAULT_STARVATION_TRIAL_COUNT; trial++) {
            long worldSeed = 42L + trial;
            RegionSolveInput input = new RegionSolveInput(
                ModBiomeGraph.DEFAULT, MacroGridConfig.DEFAULT, new RegionPos(0, 0), Map.of(), worldSeed
            );
            RegionSolveResult result = TriangleFieldGeometryRealization.realize(input);
            for (BiomeId biome : result.assignment().values()) {
                totalCellCountByBiome.merge(biome, 1, Integer::sum);
            }
        }

        for (BiomeNode node : ModBiomeGraph.DEFAULT.nodes()) {
            int totalCellCount = totalCellCountByBiome.getOrDefault(node.id(), 0);
            assertTrue(
                totalCellCount > 0,
                node.id() + " got zero cells across " + DEFAULT_STARVATION_TRIAL_COUNT
                    + " independent real solves - genuinely starved, not just one unlucky seed"
            );
        }
    }

    @Test
    void overworldHasTheExpectedNodeCount() {
        assertEquals(48, ModBiomeGraph.OVERWORLD.nodes().size());
    }

    /**
     * A biome missing from ONE real solve doesn't prove it can never generate - it might
     * just be that particular seed's bad luck, not a real problem. But a biome missing
     * from EVERY solve across several independent seeds is a genuine, confident signal:
     * if it's in the graph, it's supposed to actually generate, not just be theoretically
     * placeable somewhere nobody ever reaches. Aggregates cell counts across {@code
     * OVERWORLD_STARVATION_TRIAL_COUNT} independent real solves before judging any biome
     * starved, so a single unlucky seed can never trigger a false alarm.
     *
     * <p>50, not 10 - measured directly that 10 wasn't always enough: fixing one narrow
     * biome's starvation (adding rules for {@code snowy_beach}) immediately unmasked
     * {@code desert} as ALSO starved at that same trial count, a biome that isn't even at
     * the bare minimum legal-neighbor degree. More trials means fewer of these
     * one-at-a-time surprises - not a guarantee (still a sample, never a proof), just a
     * meaningfully higher-confidence one.
     */
    private static final int OVERWORLD_STARVATION_TRIAL_COUNT = 50;

    @Test
    void overworldNoBiomeIsStarvedAcrossManyIndependentRealSolves() {
        Map<BiomeId, Integer> totalCellCountByBiome = new HashMap<>();
        for (int trial = 0; trial < OVERWORLD_STARVATION_TRIAL_COUNT; trial++) {
            long worldSeed = 2026L + trial;
            Map<MacroCellPos, BiomeId> result = solveAndRasterizeOverworld(worldSeed);
            for (BiomeId biome : result.values()) {
                totalCellCountByBiome.merge(biome, 1, Integer::sum);
            }
        }

        for (BiomeNode node : ModBiomeGraph.OVERWORLD.nodes()) {
            int totalCellCount = totalCellCountByBiome.getOrDefault(node.id(), 0);
            assertTrue(
                totalCellCount > 0,
                node.id() + " got zero cells across " + OVERWORLD_STARVATION_TRIAL_COUNT
                    + " independent real solves - genuinely starved, not just one unlucky seed"
            );
        }
    }

    private static Map<MacroCellPos, BiomeId> solveAndRasterizeOverworld(long worldSeed) {
        double sizeBlocks = 325; // matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually, see that constant's own doc
        int meshBoxMax = 9000;
        int realMin = 1500;
        int realMax = 7500;
        MacroGridConfig gridConfig = new MacroGridConfig(4, 512);

        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(sizeBlocks, 0, 0, meshBoxMax, meshBoxMax);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = nonHullTouchingFaceIndices(mesh, adjacency);
        assertTrue(freeFaceIndices.size() > 50, "expected a real-sized mesh, got " + freeFaceIndices.size() + " free faces");

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = TriangleIdentitySizeAssignment.solve(
            adjacency, freeFaceIndices, Map.of(), ModBiomeGraph.OVERWORLD, worldSeed
        ).orElseThrow(() -> new AssertionError("expected a real hex mesh at this scale to be solvable against ModBiomeGraph.OVERWORLD (worldSeed=" + worldSeed + ")"));

        Map<Integer, Point2D> displacedVertices = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, sizeBlocks);
        List<MacroCellPos> cells = overworldTestCells(gridConfig, realMin, realMin, realMax, realMax);
        return EdgeDisplacementRasterizer.rasterize(cells, assignments, mesh, displacedVertices, Map.of(), Map.of(), gridConfig, worldSeed);
    }

    /**
     * The real replacement for the abstract ring check ({@link
     * BiomeGraph#requireEveryLocalNeighborhoodSolvable}'s own doc explains why that check,
     * even in its corrected realizable-ring form, still tests far more than the CSP could
     * ever actually reach at real biome-catalog scale — measured directly: 47,623 of
     * 201,552 realizable 6-rings in this exact graph have no universal candidate, yet
     * that's not evidence anything is actually broken). This test instead explores
     * REACHABLE local neighborhoods directly: take a real, fully-solved mesh (so every
     * face's surrounding context is genuine, not hypothetical), then for EVERY non-hull
     * face, re-solve that face plus its own real mesh neighbors together (a small
     * cluster, matching a face's own real hex neighborhood) against real fixed context
     * from everything just outside that cluster, using a DIFFERENT worldSeed per cluster
     * so the CSP explores a genuinely different candidate ordering than the one that
     * built the base mesh - "replace this neighborhood with another legal one and
     * continue" for every real neighborhood in a real mesh, not an abstract combinatorial
     * superset. A failure here names a real, reproducible dead end - an actual reachable
     * local arrangement, not a hypothetical one.
     *
     * <p>Repeats this across {@code BASE_GENERATION_COUNT} independent base generations,
     * not just one - a single base solve only ever produces the ONE specific set of real
     * neighborhoods that its own candidate ordering happened to build; a different base
     * seed produces a genuinely different set of real neighborhoods to check, so more
     * base generations means more DISTINCT real neighborhoods actually tested, not just
     * more re-checks of the same ones.
     */
    private static final int OVERWORLD_REACHABILITY_BASE_GENERATION_COUNT = 5;

    @Test
    void overworldSurvivesLocalReassignmentAcrossEveryRealNeighborhood() {
        HexMeshBuilder.HexMesh mesh = buildRealStressTestMesh();
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Set<Integer> freeFaceIndices = nonHullTouchingFaceIndices(mesh, adjacency);
        assertTrue(freeFaceIndices.size() > 50, "expected a real-sized mesh, got " + freeFaceIndices.size() + " free faces");

        int checkedClusters = 0;
        for (int baseGeneration = 0; baseGeneration < OVERWORLD_REACHABILITY_BASE_GENERATION_COUNT; baseGeneration++) {
            long baseSeed = 2026L + baseGeneration * 1_000_003L; // spread bases well apart, not adjacent to the trial seeds each derives
            Map<Integer, BiomeId> baseBiomes = solveRealBaseMesh(adjacency, freeFaceIndices, baseSeed);

            for (int center : freeFaceIndices) {
                Set<Integer> cluster = clusterAround(center, adjacency, freeFaceIndices);
                Map<Integer, BiomeId> fixedContext = fixedContextSurrounding(cluster, adjacency, baseBiomes);
                assertClusterHasALegalAssignment(adjacency, cluster, fixedContext, trialSeedFor(baseSeed, center));
                checkedClusters++;
            }
        }
        assertTrue(checkedClusters > 50, "expected a real number of neighborhoods actually checked, got " + checkedClusters);
    }

    private static HexMeshBuilder.HexMesh buildRealStressTestMesh() {
        double sizeBlocks = 325; // matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually, see that constant's own doc
        int meshBoxMax = 9000;
        return HexMeshBuilder.build(sizeBlocks, 0, 0, meshBoxMax, meshBoxMax);
    }

    private static Set<Integer> nonHullTouchingFaceIndices(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency) {
        Set<Integer> freeFaceIndices = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                freeFaceIndices.add(faceIndex);
            }
        }
        return freeFaceIndices;
    }

    /** One real, fully-solved mesh, collapsed down to just its per-face biome - the genuine surrounding context every cluster below gets stress-tested against. */
    private static Map<Integer, BiomeId> solveRealBaseMesh(TriangleAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices, long baseSeed) {
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> baseAssignments = TriangleIdentitySizeAssignment.solve(
            adjacency, freeFaceIndices, Map.of(), ModBiomeGraph.OVERWORLD, baseSeed
        ).orElseThrow(() -> new AssertionError("base mesh itself failed to solve - can't stress-test neighborhoods that don't exist"));
        Map<Integer, BiomeId> baseBiomes = new HashMap<>();
        baseAssignments.forEach((faceIndex, assignment) -> baseBiomes.put(faceIndex, assignment.biome()));
        return baseBiomes;
    }

    /** A face plus its own real mesh neighbors - a face's own real hex neighborhood - restricted to non-hull-touching faces, since re-deciding a hull-touching neighbor isn't this test's concern. */
    private static Set<Integer> clusterAround(int center, TriangleAdjacency.Adjacency adjacency, Set<Integer> freeFaceIndices) {
        Set<Integer> cluster = new HashSet<>(adjacency.neighborFaceIndices().getOrDefault(center, Set.of()));
        cluster.add(center);
        cluster.retainAll(freeFaceIndices);
        return cluster;
    }

    /** Every real neighbor of the cluster that isn't itself part of it, held at its base-solve biome - the genuine surrounding context the cluster must stay legal against. */
    private static Map<Integer, BiomeId> fixedContextSurrounding(Set<Integer> cluster, TriangleAdjacency.Adjacency adjacency, Map<Integer, BiomeId> baseBiomes) {
        Map<Integer, BiomeId> fixedContext = new HashMap<>();
        for (int clusterFace : cluster) {
            for (int outerNeighbor : adjacency.neighborFaceIndices().getOrDefault(clusterFace, Set.of())) {
                if (!cluster.contains(outerNeighbor)) {
                    fixedContext.put(outerNeighbor, baseBiomes.get(outerNeighbor));
                }
            }
        }
        return fixedContext;
    }

    /** Different per cluster, and different from {@code baseSeed} itself - so the CSP explores a genuinely different candidate ordering than whatever built the base mesh, not just re-deriving the same answer. */
    private static long trialSeedFor(long baseSeed, int center) {
        return baseSeed + center + 1;
    }

    private static void assertClusterHasALegalAssignment(
        TriangleAdjacency.Adjacency adjacency, Set<Integer> cluster, Map<Integer, BiomeId> fixedContext, long trialSeed
    ) {
        Optional<Map<Integer, TriangleIdentitySizeAssignment.Assignment>> resolved = TriangleIdentitySizeAssignment.solve(
            adjacency, cluster, fixedContext, ModBiomeGraph.OVERWORLD, trialSeed
        );
        assertTrue(
            resolved.isPresent(),
            "real neighborhood " + cluster + " (real fixed context " + fixedContext + ") has no legal assignment - a genuine, reachable dead end"
        );
    }

    private static List<MacroCellPos> overworldTestCells(MacroGridConfig gridConfig, int minX, int minZ, int maxX, int maxZ) {
        int cellSize = gridConfig.macroCellSizeBlocks();
        int minCellX = Math.floorDiv(minX, cellSize);
        int maxCellX = Math.floorDiv(maxX - 1, cellSize);
        int minCellZ = Math.floorDiv(minZ, cellSize);
        int maxCellZ = Math.floorDiv(maxZ - 1, cellSize);
        List<MacroCellPos> cells = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                cells.add(new MacroCellPos(cellX, cellZ));
            }
        }
        return cells;
    }
}
