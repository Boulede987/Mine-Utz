package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
