package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSolverTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId BADLANDS = id("badlands");
    private static final BiomeId OCEAN = id("ocean");
    private static final BiomeId FOREST = id("forest");
    private static final BiomeId PLAINS = id("plains");

    /**
     * Desert: only savanna/badlands/plains. Ocean: only forest/plains.
     * Everything else falls to default-deny. Plains is the universal filler.
     */
    private static BiomeGraph richGraph() {
        return new BiomeGraph(
            List.of(
                node("desert", 200, 300, false),
                node("savanna", 200, 300, false),
                node("badlands", 150, 250, false),
                node("ocean", 200, 300, false),
                node("forest", 150, 250, false),
                node("plains", 100, 200, true)
            ),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(DESERT, BADLANDS, 10),
                AdjacencyRule.allow(OCEAN, FOREST, 10),
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, BADLANDS, 10),
                AdjacencyRule.allow(PLAINS, OCEAN, 10),
                AdjacencyRule.allow(PLAINS, FOREST, 10)
            )
        );
    }

    private static RegionSolveInput inputFor(BiomeGraph graph, long worldSeed) {
        return new RegionSolveInput(
            graph, MacroGridConfig.DEFAULT, new RegionPos(0, 0), Map.of(), worldSeed, 300, 20
        );
    }

    @Test
    void solveIsDeterministicForSameInput() {
        RegionSolveInput input = inputFor(richGraph(), 12345L);

        RegionSolveResult first = RegionSolver.solve(input);
        RegionSolveResult second = RegionSolver.solve(input);

        assertEquals(first.assignment(), second.assignment());
        assertEquals(first.warnings(), second.warnings());
    }

    @Test
    void noAdjacencyRuleIsEverViolatedInTheResult() {
        BiomeGraph graph = richGraph();
        RegionSolveResult result = RegionSolver.solve(inputFor(graph, 999L));

        for (Map.Entry<MacroCellPos, BiomeId> entry : result.assignment().entrySet()) {
            for (MacroCellPos neighbor : entry.getKey().orthogonalNeighbors()) {
                BiomeId neighborBiome = result.assignment().get(neighbor);
                if (neighborBiome != null) {
                    assertTrue(
                        graph.isAllowed(entry.getValue(), neighborBiome),
                        entry.getValue() + " at " + entry.getKey() + " is not allowed next to " + neighborBiome + " at " + neighbor
                    );
                }
            }
        }
    }

    @Test
    void everyRegionCellGetsAssigned() {
        RegionSolveResult result = RegionSolver.solve(inputFor(richGraph(), 7L));

        assertEquals(MacroGridConfig.DEFAULT.regionSizeCellsPerSide() * MacroGridConfig.DEFAULT.regionSizeCellsPerSide(), result.assignment().size());
    }

    @Test
    void whenEveryCandidateIsTooBigForAnUndersizedRegionFirstSeedFallsBackToFiller() {
        MacroGridConfig oneCellGrid = new MacroGridConfig(128, 1);
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 700, 900, false), node("b", 700, 900, false), node("filler", 700, 900, true)),
            List.of(AdjacencyRule.allow(id("filler"), id("a"), 10), AdjacencyRule.allow(id("filler"), id("b"), 10))
        );
        RegionSolveInput input = new RegionSolveInput(graph, oneCellGrid, new RegionPos(0, 0), Map.of(), 1L, 128, 0);

        RegionSolveResult result = RegionSolver.solve(input);

        assertFalse(result.warnings().isEmpty(), "undersized region should force the fallback-to-filler path");
        assertEquals(Map.of(new MacroCellPos(0, 0), id("filler")), result.assignment());
    }

    @Test
    void fixedBoundaryCellsAreNeverOverwritten() {
        BiomeId boundaryBiome = OCEAN;
        MacroCellPos boundaryCell = new MacroCellPos(-1, 0);
        RegionSolveInput input = new RegionSolveInput(
            richGraph(), MacroGridConfig.DEFAULT, new RegionPos(0, 0), Map.of(boundaryCell, boundaryBiome), 3L, 300, 20
        );

        RegionSolveResult result = RegionSolver.solve(input);

        assertFalse(result.assignment().containsKey(boundaryCell), "fixed boundary cell belongs to the neighbor region, not this one");
    }

    @Test
    void noAssignedCellEverViolatesAdjacencyAgainstAFixedBoundaryCell() {
        BiomeGraph graph = richGraph();
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        RegionPos region = new RegionPos(0, 0);

        // Whole west edge fixed to ocean, forcing gap-fill (not just growth) to
        // reckon with the boundary somewhere along that column.
        Map<MacroCellPos, BiomeId> fixedBoundary = new HashMap<>();
        for (int z = 0; z < grid.regionSizeCellsPerSide(); z++) {
            fixedBoundary.put(new MacroCellPos(-1, z), OCEAN);
        }

        RegionSolveInput input = new RegionSolveInput(graph, grid, region, Map.copyOf(fixedBoundary), 3L, 300, 20);
        RegionSolveResult result = RegionSolver.solve(input);

        for (int z = 0; z < grid.regionSizeCellsPerSide(); z++) {
            BiomeId edgeBiome = result.assignment().get(new MacroCellPos(0, z));
            assertTrue(graph.isAllowed(edgeBiome, OCEAN), "cell (0," + z + ") = " + edgeBiome + " is not allowed next to the fixed ocean boundary");
        }
    }

    @Test
    void enforceMinimumPatchSizeMergesUndersizedPatchIntoItsLargestLegalNeighbor() {
        BiomeGraph graph = richGraph();
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        // Desert's minWidthBlocks (200) maps to a 2-cell minimum at this grid's
        // cell size; a lone 1-cell desert patch is undersized.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, SAVANNA);
        }
        MacroCellPos undersizedCell = new MacroCellPos(3, 3);
        assignment.put(undersizedCell, DESERT);

        RegionSolver.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        assertEquals(SAVANNA, assignment.get(undersizedCell), "1-cell desert patch is under its 2-cell minimum and should be absorbed");
    }

    @Test
    void enforceMinimumPatchSizeFallsBackToFillerWhenNoNeighborIsLegal() {
        BiomeGraph graph = richGraph();
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        // 1-cell desert patch bordering both ocean and badlands, which aren't
        // legal next to each other (no rule between them, default-deny).
        // Neither can legally absorb the patch without also touching the other.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, OCEAN);
        }
        MacroCellPos undersizedCell = new MacroCellPos(3, 3);
        MacroCellPos badlandsNeighbor = new MacroCellPos(3, 2);
        assignment.put(undersizedCell, DESERT);
        assignment.put(badlandsNeighbor, BADLANDS);

        RegionSolver.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        assertEquals(PLAINS, assignment.get(undersizedCell), "no legal neighbor to merge into should fall back to the universal filler");
    }

    @Test
    void enforceMinimumPatchSizeLeavesUndersizedFillerPatchesAlone() {
        // Filler's own declared minimum is deliberately large here so a 1-cell
        // filler patch really is undersized by the same test that would trigger
        // a merge for any other biome - the point is that it's exempt anyway.
        BiomeId filler = id("filler");
        BiomeId savanna = id("savanna");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("filler", 1000, 1000, true), node("savanna", 100, 200, false)),
            List.of(AdjacencyRule.allow(filler, savanna, 10))
        );
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, savanna);
        }
        MacroCellPos fillerCell = new MacroCellPos(3, 3);
        assignment.put(fillerCell, filler);

        RegionSolver.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        assertEquals(filler, assignment.get(fillerCell), "universal-filler patches are the fallback of last resort, not something to merge away");
    }
}
