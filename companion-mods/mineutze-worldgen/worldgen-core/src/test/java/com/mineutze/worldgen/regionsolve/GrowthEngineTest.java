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
            List.of(node("desert", 100, 900), node("plains", 100, 900), node("ocean", 100, 900)),
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

    // A dedicated, much bigger grid for the wall-gap tests below - they need ~90 legally
    // reachable cells AND every tested coordinate (seed, wall, gap, crossing target) to sit
    // comfortably beyond WARP_AMPLITUDE_CELLS_CAP (8 cells, MacroGridConfig's own hard cap
    // regardless of region size) from every region edge, so the region-grid warp (unrelated
    // to what these tests actually check) can never coincidentally pull a tested cell into
    // a neighboring region. The shared 16-wide GRID/REGION above is too small for that
    // margin AND the cell budget at once - discovered when the warp went fractal (multi-
    // octave, see MacroGridConfig's own doc) and a different phase at WORLD_SEED=1 pushed
    // the old x=2 seed at region size 16 into the neighboring region entirely.
    private static final MacroGridConfig WALL_GAP_GRID = new MacroGridConfig(128, 64);
    private static final RegionPos WALL_GAP_REGION = new RegionPos(0, 0);

    // Chamber geometry for the wall-gap tests: a 5-column x 16-row pocket (80 cells,
    // deliberately < the 90 minCells below) walled off on 3 sides, with a single gap in
    // the 4th wall - forces growth to cross through the gap to reach the far (effectively
    // unbounded) side to hit its target. The original version of this test got that
    // same 80-cell confinement for free from the real region edge (a tiny 16-wide region);
    // WALL_GAP_GRID is deliberately big so every tested coordinate stays clear of the
    // region-grid warp (see WALL_GAP_GRID's own doc), so this test has to build its own
    // explicit walls instead of relying on the true region boundary.
    private static final int CROSS_WALL_X = 31;
    private static final int LEFT_WALL_X = 25;
    private static final int TOP_WALL_Z = 15;
    private static final int BOTTOM_WALL_Z = 32;
    private static final int GAP_Z = 24;

    /**
     * A wall of externally-assigned PLAINS (legal next to desert, so isLegal alone
     * never blocks it) splits a chamber at x={@value #CROSS_WALL_X}, with exactly one
     * gap at z={@value #GAP_Z}. Growth must funnel through that single cell to reach
     * the far side.
     */
    private static Map<MacroCellPos, BiomeId> wallWithOneGap() {
        Map<MacroCellPos, BiomeId> wall = new HashMap<>();
        for (int z = TOP_WALL_Z; z <= BOTTOM_WALL_Z; z++) {
            if (z != GAP_Z) {
                wall.put(new MacroCellPos(CROSS_WALL_X, z), PLAINS);
            }
            wall.put(new MacroCellPos(LEFT_WALL_X, z), PLAINS);
        }
        for (int x = LEFT_WALL_X; x <= CROSS_WALL_X; x++) {
            wall.put(new MacroCellPos(x, TOP_WALL_Z), PLAINS);
            wall.put(new MacroCellPos(x, BOTTOM_WALL_Z), PLAINS);
        }
        return wall;
    }

    @Test
    void unrestrictedProfileCanSnakeThroughAOneCellGap() {
        BiomeGraph graph = new BiomeGraph(
            List.of(
                node("desert", 100, 900, GrowthCohesionProfile.UNRESTRICTED),
                node("plains", 100, 900)
            ),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10))
        );

        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            new MacroCellPos(27, 20), DESERT, 90, 90, WALL_GAP_REGION, WALL_GAP_GRID, graph, wallWithOneGap(), WORLD_SEED
        );

        assertTrue(attempt.success(), "unrestricted growth should be able to squeeze through the 1-cell gap to reach enough cells");
        assertTrue(attempt.claimedCells().containsKey(new MacroCellPos(CROSS_WALL_X + 1, GAP_Z)), "must have crossed to the far side of the wall");
    }

    @Test
    void compactProfileRefusesToSnakeThroughAOneCellGap() {
        GrowthCohesionProfile compact = new GrowthCohesionProfile(2, 0, 0);
        BiomeGraph graph = new BiomeGraph(
            List.of(
                node("desert", 100, 900, compact),
                node("plains", 100, 900)
            ),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10))
        );

        GrowthEngine.GrowthAttempt attempt = GrowthEngine.attemptGrowth(
            new MacroCellPos(27, 20), DESERT, 5, 90, WALL_GAP_REGION, WALL_GAP_GRID, graph, wallWithOneGap(), WORLD_SEED
        );

        assertFalse(
            attempt.claimedCells().containsKey(new MacroCellPos(CROSS_WALL_X + 1, GAP_Z)),
            "a patch requiring 2 same-biome neighbors must never cross a 1-cell-wide gap — the gap cell only ever touches 1 claimed neighbor"
        );
    }
}
