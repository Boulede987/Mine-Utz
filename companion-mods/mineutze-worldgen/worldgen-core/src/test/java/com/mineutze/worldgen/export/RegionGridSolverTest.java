package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct test - flagged by this session's test-coverage audit as having zero dedicated
 * tests despite being the exact solve path the standalone export CLI drives, and this
 * session's own repeated manual verification target. Same 4-biome fixture already proven
 * safe for a full growth run in TriangleFieldGeometryRealizationTest (needs a diagonal
 * hub to avoid a genuine 4-way dead end - see that test's own doc).
 *
 * <p>Deliberately regionSpan=1 throughout, not a real multi-region swath: a 2x2 swath at
 * this fixture's grid size was measured to push the full suite from ~6s to ~2.5 minutes
 * (PatchSizeEnforcer/PixelFragmentRepair's whole-swath passes scale badly across multiple
 * regions - matches this codebase's own documented RegionSolveStageTimingSpikeTest
 * finding). Cross-region boundary-stitching correctness is already SeedRegionCache's own
 * dedicated test suite's job; this class only needs to prove its own driver loop, the
 * whole-swath cleanup call, and ExportSnapshot assembly work - none of which need more
 * than one region to exercise.
 */
class RegionGridSolverTest {

    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(4, 256);

    private static BiomeId id(String path) {
        return new BiomeId("test", path);
    }

    private static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeNode(id(path), new BiomeSizeRange(minWidthBlocks, maxWidthBlocks));
    }

    private static BiomeGraph fourBiomeGraph() {
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

    @Test
    void solveAssemblesAFullyGaplessSnapshot() {
        ExportSnapshot snapshot = RegionGridSolver.solve(fourBiomeGraph(), SMALL_GRID, 42L, new RegionPos(0, 0), 1, (region, result) -> { });

        assertEquals(new RegionPos(0, 0), snapshot.minRegion());
        assertEquals(1, snapshot.regionSpan());
        for (MacroCellPos cell : snapshot.orderedCells()) {
            assertTrue(snapshot.cells().containsKey(cell), "cell " + cell + " missing from the assembled swath");
        }
    }

    @Test
    void onRegionSolvedFiresExactlyOncePerRegionInTheSwath() {
        List<RegionPos> solvedRegions = new ArrayList<>();

        RegionGridSolver.solve(fourBiomeGraph(), SMALL_GRID, 42L, new RegionPos(1, 1), 1, (region, result) -> solvedRegions.add(region));

        assertEquals(List.of(new RegionPos(1, 1)), solvedRegions, "each region must be solved exactly once, not revisited");
    }

    @Test
    void producesZeroIllegalAdjacenciesAfterWholeSwathCleanup() {
        BiomeGraph graph = fourBiomeGraph();
        ExportSnapshot snapshot = RegionGridSolver.solve(graph, SMALL_GRID, 2026L, new RegionPos(0, 0), 1, (region, result) -> { });

        int illegal = 0;
        for (Map.Entry<MacroCellPos, BiomeId> entry : snapshot.cells().entrySet()) {
            for (MacroCellPos neighbor : mooreNeighbors(entry.getKey())) {
                BiomeId neighborBiome = snapshot.cells().get(neighbor);
                if (neighborBiome != null && !entry.getValue().equals(neighborBiome) && !graph.isAllowed(entry.getValue(), neighborBiome)) {
                    illegal++;
                }
            }
        }
        assertEquals(0, illegal, "found an illegal Moore-adjacent biome pair after PatchSizeEnforcer/PixelFragmentRepair's own whole-swath cleanup");
    }

    private static List<MacroCellPos> mooreNeighbors(MacroCellPos cell) {
        List<MacroCellPos> neighbors = new ArrayList<>(cell.orthogonalNeighbors());
        neighbors.addAll(cell.diagonalNeighbors());
        return neighbors;
    }
}
