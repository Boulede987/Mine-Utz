package com.mineutze.worldgen.regionsolve;

import java.util.ArrayList;
import java.util.List;

/**
 * The macro-cell/solve-region grid the constraint solver operates on. See
 * doc/ARCHITECTURE.md "Decided" — 128-block cells, 16x16-cell regions are
 * placeholders to be tuned from logged solve output, not derived here.
 */
public record MacroGridConfig(int macroCellSizeBlocks, int regionSizeCellsPerSide) {

    public static final MacroGridConfig DEFAULT = new MacroGridConfig(128, 16);

    public MacroGridConfig {
        if (macroCellSizeBlocks <= 0) {
            throw new IllegalArgumentException("macroCellSizeBlocks must be positive, got " + macroCellSizeBlocks);
        }
        if (regionSizeCellsPerSide <= 0) {
            throw new IllegalArgumentException("regionSizeCellsPerSide must be positive, got " + regionSizeCellsPerSide);
        }
    }

    public RegionPos regionOf(MacroCellPos cell) {
        return new RegionPos(
            Math.floorDiv(cell.x(), regionSizeCellsPerSide),
            Math.floorDiv(cell.z(), regionSizeCellsPerSide)
        );
    }

    public List<MacroCellPos> cellsInRegion(RegionPos region) {
        int minX = region.x() * regionSizeCellsPerSide;
        int minZ = region.z() * regionSizeCellsPerSide;
        List<MacroCellPos> cells = new ArrayList<>(regionSizeCellsPerSide * regionSizeCellsPerSide);
        for (int x = minX; x < minX + regionSizeCellsPerSide; x++) {
            for (int z = minZ; z < minZ + regionSizeCellsPerSide; z++) {
                cells.add(new MacroCellPos(x, z));
            }
        }
        return cells;
    }

    /** Approximates a biome's target width as a square patch of macro-cells. */
    public int cellCountForWidthBlocks(int widthBlocks) {
        double sideInCells = (double) widthBlocks / macroCellSizeBlocks;
        return Math.max(1, (int) Math.round(sideInCells * sideInCells));
    }
}
