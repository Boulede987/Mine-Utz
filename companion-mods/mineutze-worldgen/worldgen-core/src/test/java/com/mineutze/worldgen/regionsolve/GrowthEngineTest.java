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

class GrowthEngineTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(128, 16);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final BiomeId DESERT = id("desert");
    private static final BiomeId PLAINS = id("plains");
    private static final BiomeId OCEAN = id("ocean");

    private static BiomeGraph graphWithDesertForbiddenNextToOcean() {
        return new BiomeGraph(
            List.of(node("desert", 100, 900, false), node("plains", 100, 900, true), node("ocean", 100, 900, false)),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10), AdjacencyRule.allow(PLAINS, OCEAN, 10))
        );
    }

    @Test
    void growsToAtLeastMinimumSizeWhenUnobstructed() {
        BiomeGraph graph = graphWithDesertForbiddenNextToOcean();
        MacroCellPos seed = new MacroCellPos(8, 8);

        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            seed, DESERT, 20, 20, REGION, GRID, graph, Map.of()
        );

        assertTrue(attempt.success());
        assertEquals(20, attempt.claimedCells().size());
        assertTrue(attempt.claimedCells().values().stream().allMatch(DESERT::equals));
    }

    @Test
    void refusesToClaimSeedCellAdjacentToForbiddenNeighbor() {
        BiomeGraph graph = graphWithDesertForbiddenNextToOcean();
        MacroCellPos seed = new MacroCellPos(8, 8);
        Map<MacroCellPos, BiomeId> externallyAssigned = Map.of(new MacroCellPos(9, 8), OCEAN);

        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            seed, DESERT, 5, 20, REGION, GRID, graph, externallyAssigned
        );

        assertFalse(attempt.success());
        assertTrue(attempt.claimedCells().isEmpty());
    }

    @Test
    void stopsGrowingAtRegionBoundaryAndAllowedNeighborRingDoesNotBlock() {
        BiomeGraph graph = graphWithDesertForbiddenNextToOcean();
        MacroCellPos seed = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> externallyAssigned = Map.of(new MacroCellPos(1, 0), PLAINS);

        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            seed, DESERT, 1, 5, REGION, GRID, graph, externallyAssigned
        );

        assertTrue(attempt.success());
        assertFalse(attempt.claimedCells().containsKey(new MacroCellPos(1, 0)), "must not claim an externally-assigned cell");
    }
}
