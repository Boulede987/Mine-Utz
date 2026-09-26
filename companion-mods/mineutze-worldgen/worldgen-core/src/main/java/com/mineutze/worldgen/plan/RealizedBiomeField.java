package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;
import java.util.Map;

/**
 * The biome at ANY point of the plane (2026-09-20) - what world generation will ask, one block or
 * one quart at a time. The point is displaced by the {@link DivergenceFreeWarp}, the hexagon under
 * the displaced point is found, and its biome is returned. Every step repeats exactly after each
 * tile vector (the warp's waves sit on the tile's reciprocal lattice, and hexagon lookup wraps),
 * so the map tiles the plane with no seam: a border that reaches a tile edge simply continues into
 * the next tile.
 */
final class RealizedBiomeField {

    private final HexTorusGeometry geometry;
    private final DivergenceFreeWarp warp;
    private final int[] biomeOfCell;
    private final int gridWidth;

    RealizedBiomeField(ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern, HexTorusGeometry geometry, DivergenceFreeWarp warp, List<BiomeId> biomeOrder) {
        this.geometry = geometry;
        this.warp = warp;
        this.gridWidth = grid.width();
        this.biomeOfCell = new int[grid.cellCount()];
        for (int r = 0; r < grid.height(); r++) {
            for (int q = 0; q < grid.width(); q++) {
                assignCellBiome(grid, pattern, biomeOrder, q, r);
            }
        }
    }

    private void assignCellBiome(ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern, List<BiomeId> biomeOrder, int q, int r) {
        BiomeId biome = pattern.get(new ToroidalPos(q, r));
        if (biome == null) {
            throw new IllegalArgumentException("RealizedBiomeField: the pattern has no biome for cell (" + q + "," + r + ")");
        }
        int index = biomeOrder.indexOf(biome);
        if (index < 0) {
            throw new IllegalArgumentException("RealizedBiomeField: " + biome + " is used by the pattern but missing from the biome order");
        }
        biomeOfCell[r * grid.width() + q] = index;
    }

    /** Index (row * width + column) of the pattern cell whose hexagon holds the displaced point. */
    int cellIndexAt(double x, double y) {
        double[] displacement = new double[2];
        warp.displace(x, y, displacement);
        ToroidalPos cell = geometry.cellAt(x + displacement[0], y + displacement[1]);
        return cell.r() * gridWidth + cell.q();
    }

    /** Position in the biome order given at construction of the biome at (x, y). */
    int biomeIndexAt(double x, double y) {
        return biomeOfCell[cellIndexAt(x, y)];
    }

    /** Position in the biome order of the biome of pattern cell {@code cellIndex} (row * width + column). */
    int biomeOfCell(int cellIndex) {
        return biomeOfCell[cellIndex];
    }
}
