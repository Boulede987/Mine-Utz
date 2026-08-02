package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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
    private static final long WORLD_SEED = 1L;

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
            seed, DESERT, 20, 20, REGION, GRID, graph, Map.of(), WORLD_SEED
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
            seed, DESERT, 5, 20, REGION, GRID, graph, externallyAssigned, WORLD_SEED
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
            seed, DESERT, 1, 5, REGION, GRID, graph, externallyAssigned, WORLD_SEED
        );

        assertTrue(attempt.success());
        assertFalse(attempt.claimedCells().containsKey(new MacroCellPos(1, 0)), "must not claim an externally-assigned cell");
    }

    /**
     * A wall of externally-assigned PLAINS (legal next to desert, so isLegal alone
     * never blocks it) splits the 16x16 region at x=5, with exactly one gap at
     * (5, 8). Growth must funnel through that single cell to reach the far side.
     */
    private static Map<MacroCellPos, BiomeId> wallWithOneGap() {
        Map<MacroCellPos, BiomeId> wall = new HashMap<>();
        for (int z = 0; z < 16; z++) {
            if (z != 8) {
                wall.put(new MacroCellPos(5, z), PLAINS);
            }
        }
        return wall;
    }

    @Test
    void unrestrictedProfileCanSnakeThroughAOneCellGap() {
        BiomeGraph graph = new BiomeGraph(
            List.of(
                node("desert", 100, 900, false, GrowthCohesionProfile.UNRESTRICTED),
                node("plains", 100, 900, true)
            ),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10))
        );

        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            new MacroCellPos(2, 8), DESERT, 90, 90, REGION, GRID, graph, wallWithOneGap(), WORLD_SEED
        );

        assertTrue(attempt.success(), "unrestricted growth should be able to squeeze through the 1-cell gap to reach enough cells");
        assertTrue(attempt.claimedCells().containsKey(new MacroCellPos(6, 8)), "must have crossed to the far side of the wall");
    }

    @Test
    void compactProfileRefusesToSnakeThroughAOneCellGap() {
        GrowthCohesionProfile compact = new GrowthCohesionProfile(2, 0, 0);
        BiomeGraph graph = new BiomeGraph(
            List.of(
                node("desert", 100, 900, false, compact),
                node("plains", 100, 900, true)
            ),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10))
        );

        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            new MacroCellPos(2, 8), DESERT, 5, 90, REGION, GRID, graph, wallWithOneGap(), WORLD_SEED
        );

        assertFalse(
            attempt.claimedCells().containsKey(new MacroCellPos(6, 8)),
            "a patch requiring 2 same-biome neighbors must never cross a 1-cell-wide gap — the gap cell only ever touches 1 claimed neighbor"
        );
    }
}
