package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TopologyFirstRegionSolverTest {

    private static final long WORLD_SEED = 42L;

    @Test
    void solvesASimpleRegionWithoutAnyConflict() {
        BiomeId biomeA = TestBiomeNodes.id("a");
        BiomeId biomeB = TestBiomeNodes.id("b");
        BiomeGraph graph = new BiomeGraph(
            List.of(TestBiomeNodes.node("a", 100, 300), TestBiomeNodes.node("b", 100, 300)),
            List.of(AdjacencyRule.allow(biomeA, biomeB, 10))
        );
        MacroGridConfig grid = new MacroGridConfig(64, 8);
        RegionSolveInput input = new RegionSolveInput(graph, grid, new RegionPos(0, 0), Map.of(), WORLD_SEED);

        Map<MacroCellPos, BiomeId> result = TopologyFirstRegionSolver.solve(input, 200);

        assertEquals(grid.regionSizeCellsPerSide() * grid.regionSizeCellsPerSide(), result.size());
        assertNoIllegalAdjacency(result, graph);
    }

    /**
     * A 1x1 region so SeedPlacer's jitter is irrelevant — every reseed
     * attempt places the exact same single seed at the exact same position,
     * wedged between two fixed boundary neighbors (west = x, east = y) whose
     * legal sets share nothing (x's only allies: x, a; y's only allies: y,
     * b — the x-a-b-y chain bridges them for BiomeGraph's own eager
     * validation without ever making x directly legal next to y). Reseeding
     * genuinely cannot help here, proving the node-drop tier (not reseed)
     * is what makes this terminate: drop the one free node to zero, CSP
     * succeeds vacuously, and GapFiller's own already-accepted last-resort
     * tier settles the single remaining pixel-level conflict, exactly as it
     * already does in the pre-redesign pipeline.
     */
    @Test
    void dropsAPositionallyInvariantWedgedNodeAndStillTerminates() {
        BiomeId biomeX = TestBiomeNodes.id("x");
        BiomeId biomeA = TestBiomeNodes.id("a");
        BiomeId biomeB = TestBiomeNodes.id("b");
        BiomeId biomeY = TestBiomeNodes.id("y");
        BiomeGraph graph = new BiomeGraph(
            List.of(TestBiomeNodes.node("x", 100, 300), TestBiomeNodes.node("a", 100, 300), TestBiomeNodes.node("b", 100, 300), TestBiomeNodes.node("y", 100, 300)),
            List.of(
                AdjacencyRule.allow(biomeX, biomeA, 10),
                AdjacencyRule.allow(biomeA, biomeB, 10),
                AdjacencyRule.allow(biomeB, biomeY, 10)
            )
        );
        MacroGridConfig oneCellRegion = new MacroGridConfig(64, 1);
        Map<MacroCellPos, BiomeId> fixedBoundaryCells = Map.of(
            new MacroCellPos(-1, 0), biomeX,
            new MacroCellPos(1, 0), biomeY
        );
        RegionSolveInput input = new RegionSolveInput(graph, oneCellRegion, new RegionPos(0, 0), fixedBoundaryCells, WORLD_SEED);

        Map<MacroCellPos, BiomeId> result = assertDoesNotThrow(() -> TopologyFirstRegionSolver.solve(input, 1000));

        assertEquals(1, result.size());
        assertTrue(result.containsKey(new MacroCellPos(0, 0)));
    }

    private static void assertNoIllegalAdjacency(Map<MacroCellPos, BiomeId> result, BiomeGraph graph) {
        for (Map.Entry<MacroCellPos, BiomeId> entry : result.entrySet()) {
            for (MacroCellPos neighbor : entry.getKey().orthogonalNeighbors()) {
                BiomeId neighborBiome = result.get(neighbor);
                if (neighborBiome != null) {
                    assertTrue(graph.isAllowed(entry.getValue(), neighborBiome),
                        entry.getValue() + " at " + entry.getKey() + " illegally touches " + neighborBiome + " at " + neighbor);
                }
            }
        }
    }
}
