package com.mineutze.worldgen.regionsolve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedPlacerTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(128, 16);
    private static final long WORLD_SEED = 42L;

    @Test
    void samePlacementForSameInputs() {
        RegionPos region = new RegionPos(3, -2);

        List<MacroCellPos> first = SeedPlacer.placeSeeds(region, GRID, WORLD_SEED, 300);
        List<MacroCellPos> second = SeedPlacer.placeSeeds(region, GRID, WORLD_SEED, 300);

        assertEquals(first, second);
    }

    @Test
    void seedsStayWithinRegionBounds() {
        RegionPos region = new RegionPos(1, 1);
        Set<MacroCellPos> regionCells = Set.copyOf(GRID.cellsInRegion(region));

        List<MacroCellPos> seeds = SeedPlacer.placeSeeds(region, GRID, WORLD_SEED, 300);

        assertFalse(seeds.isEmpty());
        assertTrue(regionCells.containsAll(seeds));
    }

    @Test
    void differentRegionsGetDifferentSeedPatterns() {
        List<MacroCellPos> regionA = SeedPlacer.placeSeeds(new RegionPos(0, 0), GRID, WORLD_SEED, 300);
        List<MacroCellPos> regionB = SeedPlacer.placeSeeds(new RegionPos(5, 5), GRID, WORLD_SEED, 300);

        assertFalse(regionA.equals(regionB));
    }
}
