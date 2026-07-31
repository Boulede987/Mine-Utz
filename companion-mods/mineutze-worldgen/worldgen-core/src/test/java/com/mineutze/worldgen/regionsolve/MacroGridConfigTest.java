package com.mineutze.worldgen.regionsolve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroGridConfigTest {

    @Test
    void cellCountApproximatesSquareOfWidth() {
        MacroGridConfig grid = new MacroGridConfig(128, 16);

        assertEquals(30, grid.cellCountForWidthBlocks(700));
        assertEquals(61, grid.cellCountForWidthBlocks(1000));
    }

    @Test
    void regionOfAndCellsInRegionRoundTrip() {
        MacroGridConfig grid = new MacroGridConfig(128, 16);
        RegionPos region = new RegionPos(2, -3);

        List<MacroCellPos> cells = grid.cellsInRegion(region);

        assertEquals(256, cells.size());
        assertTrue(cells.stream().allMatch(cell -> grid.regionOf(cell).equals(region)));
    }

    @Test
    void regionOfHandlesNegativeCoordinatesCorrectly() {
        MacroGridConfig grid = new MacroGridConfig(128, 16);

        assertEquals(new RegionPos(-1, -1), grid.regionOf(new MacroCellPos(-1, -1)));
        assertEquals(new RegionPos(-1, 0), grid.regionOf(new MacroCellPos(-16, 0)));
    }
}
