package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetPlannerTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId PLAINS = id("plains");

    private static BiomeGraph smallGraph() {
        return new BiomeGraph(
            List.of(node("desert", 700, 1000), node("savanna", 500, 800), node("plains", 300, 600)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10), AdjacencyRule.allow(PLAINS, DESERT, 10), AdjacencyRule.allow(PLAINS, SAVANNA, 10))
        );
    }

    @Test
    void totalDemandNeverExceedsRegionCapacity() {
        BiomeGraph graph = smallGraph();
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        int capacity = grid.regionSizeCellsPerSide() * grid.regionSizeCellsPerSide();

        List<PatchDemand> plan = BudgetPlanner.plan(graph, grid, new RegionPos(0, 0), 42L);

        int total = plan.stream().mapToInt(PatchDemand::targetCells).sum();
        assertTrue(total <= capacity, "total budgeted demand (" + total + ") must never exceed region capacity (" + capacity + ")");
    }

    @Test
    void planIsNonEmptyForARegionBigEnoughToFitSomething() {
        List<PatchDemand> plan = BudgetPlanner.plan(smallGraph(), MacroGridConfig.DEFAULT, new RegionPos(0, 0), 1L);

        assertFalse(plan.isEmpty());
    }

    @Test
    void planIsSortedLargestFirst() {
        List<PatchDemand> plan = BudgetPlanner.plan(smallGraph(), MacroGridConfig.DEFAULT, new RegionPos(0, 0), 7L);

        List<PatchDemand> sorted = plan.stream().sorted(Comparator.comparingInt(PatchDemand::targetCells).reversed()).toList();
        assertEquals(sorted, plan);
    }

    @Test
    void everyDemandRespectsItsOwnBiomesDeclaredSizeRange() {
        BiomeGraph graph = smallGraph();
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        List<PatchDemand> plan = BudgetPlanner.plan(graph, grid, new RegionPos(0, 0), 3L);

        for (PatchDemand demand : plan) {
            var node = graph.node(demand.biomeId());
            int minCells = grid.cellCountForWidthBlocks(node.sizeRange().minWidthBlocks());
            int maxCells = grid.cellCountForWidthBlocks(node.sizeRange().maxWidthBlocks());
            assertEquals(minCells, demand.minCells());
            assertTrue(demand.targetCells() >= minCells && demand.targetCells() <= maxCells, demand + " outside its own declared range");
        }
    }

    @Test
    void aRegionTooSmallForAnyBiomesMinimumGetsAnEmptyPlan() {
        MacroGridConfig tinyGrid = new MacroGridConfig(64, 1); // 1 cell total
        List<PatchDemand> plan = BudgetPlanner.plan(smallGraph(), tinyGrid, new RegionPos(0, 0), 1L);

        assertTrue(plan.isEmpty(), "no node's minCells (all far above 1) can fit a 1-cell region");
    }

    @Test
    void sameInputsProduceTheSamePlan() {
        List<PatchDemand> first = BudgetPlanner.plan(smallGraph(), MacroGridConfig.DEFAULT, new RegionPos(5, 5), 99L);
        List<PatchDemand> second = BudgetPlanner.plan(smallGraph(), MacroGridConfig.DEFAULT, new RegionPos(5, 5), 99L);

        assertEquals(first, second);
    }
}
