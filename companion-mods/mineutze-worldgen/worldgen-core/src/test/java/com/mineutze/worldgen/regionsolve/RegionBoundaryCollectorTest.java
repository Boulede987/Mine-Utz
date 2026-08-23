package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cellsBorderingRegion} used to keep only a single-macro-cell-wide ring at the
 * literal seam - far too thin, since a real face centroid in a region's own mesh can land
 * up to {@link TriangleFieldGeometryRealization#TRIANGULATION_MARGIN_BLOCKS} blocks into an
 * already-solved neighbor. These prove the widened collection depth is a real, precise
 * bound (matching that same constant), not "collect everything" nor still "collect almost
 * nothing".
 */
class RegionBoundaryCollectorTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(4, 64);
    private static final BiomeId DESERT = id("desert");

    @Test
    void collectsCellsWellBeyondTheOldSingleCellRingButWithinTheTriangulationMargin() {
        RegionPos region = new RegionPos(0, 0);
        RegionPos neighbor = new RegionPos(1, 0);
        int cellSize = GRID.macroCellSizeBlocks();
        int maxIncludedBlockX = GRID.regionSizeCellsPerSide() * cellSize + GRID.warpMarginCells() * cellSize
            + TriangleFieldGeometryRealization.TRIANGULATION_MARGIN_BLOCKS;

        // Well past the old 4-block ring, but one cell shy of the real inclusion bound.
        MacroCellPos farButRelevant = new MacroCellPos((maxIncludedBlockX - cellSize) / cellSize, 25);
        Map<MacroCellPos, BiomeId> neighborAssignment = Map.of(farButRelevant, DESERT);

        Map<MacroCellPos, BiomeId> fixedBoundary = RegionBoundaryCollector.collect(
            region, GRID, 1L, candidate -> candidate.equals(neighbor) ? neighborAssignment : null
        );

        assertTrue(
            fixedBoundary.containsKey(farButRelevant),
            "a cell within TRIANGULATION_MARGIN_BLOCKS of the border, but far past the old 1-cell ring, must be collected"
        );
    }

    @Test
    void excludesCellsBeyondTheTriangulationMargin() {
        RegionPos region = new RegionPos(0, 0);
        RegionPos neighbor = new RegionPos(1, 0);
        int cellSize = GRID.macroCellSizeBlocks();
        int maxIncludedBlockX = GRID.regionSizeCellsPerSide() * cellSize + GRID.warpMarginCells() * cellSize
            + TriangleFieldGeometryRealization.TRIANGULATION_MARGIN_BLOCKS;

        // Comfortably past the real inclusion bound - no real face centroid in region
        // (0,0)'s own mesh could ever reach this far.
        MacroCellPos tooFar = new MacroCellPos((maxIncludedBlockX + 400) / cellSize, 25);
        Map<MacroCellPos, BiomeId> neighborAssignment = Map.of(tooFar, DESERT);

        Map<MacroCellPos, BiomeId> fixedBoundary = RegionBoundaryCollector.collect(
            region, GRID, 1L, candidate -> candidate.equals(neighbor) ? neighborAssignment : null
        );

        assertFalse(fixedBoundary.containsKey(tooFar), "a cell past the triangulation margin must not be collected");
    }

    @Test
    void neverConsultsAnUnsolvedNeighbor() {
        RegionPos region = new RegionPos(0, 0);
        Map<MacroCellPos, BiomeId> fixedBoundary = RegionBoundaryCollector.collect(region, GRID, 1L, candidate -> null);
        assertTrue(fixedBoundary.isEmpty(), "no neighbor is solved, so there is nothing real to fix against");
    }
}
