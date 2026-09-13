package com.mineutze.worldgen.regionsolve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroGridConfigTest {

    private static final long WORLD_SEED = 42L;

    @Test
    void cellCountApproximatesSquareOfWidth() {
        MacroGridConfig grid = new MacroGridConfig(128, 16);

        assertEquals(30, grid.cellCountForWidthBlocks(700));
        assertEquals(61, grid.cellCountForWidthBlocks(1000));
    }

    /** Warping means region no longer perfect 16x16 square, so size only asserted "close," not exact - real invariant is round-trip. */
    @Test
    void regionOfAndCellsInRegionRoundTrip() {
        MacroGridConfig grid = new MacroGridConfig(128, 16);
        RegionPos region = new RegionPos(2, -3);

        List<MacroCellPos> cells = grid.cellsInRegion(region, WORLD_SEED);

        assertTrue(cells.size() > 200 && cells.size() < 320, "warped region size should stay close to the nominal 256, got " + cells.size());
        assertTrue(cells.stream().allMatch(cell -> grid.regionOf(cell, WORLD_SEED).equals(region)));
    }

    @Test
    void regionOfHandlesNegativeCoordinatesCorrectly() {
        MacroGridConfig grid = new MacroGridConfig(128, 16);

        // Interior cells, far from any warp-affected boundary, land in same region unwarped
        // floor-division would predict - warp amplitude (few cells) can't reach this far
        // from boundary. -16 sits exactly ON region-16 boundary (not interior at all), so
        // excluded here - use -8 on both axes instead.
        assertEquals(new RegionPos(-1, -1), grid.regionOf(new MacroCellPos(-8, -8), WORLD_SEED));
        assertEquals(new RegionPos(-1, 0), grid.regionOf(new MacroCellPos(-8, 8), WORLD_SEED));
    }

    /**
     * Rest of codebase (RegionBoundaryCollector.neighborsOf's 8-neighbor Moore set,
     * SeedRegionCache's concurrent-race locking) depends on region's true geometric
     * neighbors never reaching past its immediate 8 neighbors - only holds if warp
     * amplitude stays small relative to regionSizeCellsPerSide. Regression guard: for wide
     * span of cells, warped region must never differ from UNWARPED (plain floor-division)
     * region by more than 1 step on either axis.
     */
    @Test
    void warpNeverBleedsPastTheImmediateNeighborRegion() {
        MacroGridConfig grid = MacroGridConfig.DEFAULT;

        for (int x = -200; x <= 200; x += 3) {
            for (int z = -200; z <= 200; z += 3) {
                MacroCellPos cell = new MacroCellPos(x, z);
                RegionPos warped = grid.regionOf(cell, WORLD_SEED);
                RegionPos unwarped = new RegionPos(
                    Math.floorDiv(x, grid.regionSizeCellsPerSide()), Math.floorDiv(z, grid.regionSizeCellsPerSide())
                );
                assertTrue(
                    Math.abs(warped.x() - unwarped.x()) <= 1 && Math.abs(warped.z() - unwarped.z()) <= 1,
                    cell + " warped to " + warped + ", more than 1 region step from the unwarped " + unwarped
                );
            }
        }
    }

    @Test
    void differentWorldSeedsProduceDifferentlyShapedRegions() {
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        RegionPos region = new RegionPos(0, 0);

        List<MacroCellPos> cellsSeedA = grid.cellsInRegion(region, 1L);
        List<MacroCellPos> cellsSeedB = grid.cellsInRegion(region, 2L);

        assertTrue(!cellsSeedA.equals(cellsSeedB), "different world seeds should warp the same region differently");
    }
}
