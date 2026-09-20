package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Turns a solved cell pattern into a biome for every sample of a tile (2026-09-20): each sample
 * asks the {@link DivergenceFreeWarp} where to look, finds the hexagon there and takes its
 * biome. With no warp the result is the raw hexagon pattern; with a warp the borders bend.
 */
final class BiomeMapRasterizer {

    private BiomeMapRasterizer() {
    }

    /** {@code biomeOrder} fixes the number each biome gets in the map; it must contain every biome the pattern uses. */
    static BiomeMap rasterize(
        ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern, HexTorusGeometry geometry, DivergenceFreeWarp warp, int samplesPerSide, List<BiomeId> biomeOrder
    ) {
        if (samplesPerSide < 1) {
            throw new IllegalArgumentException("BiomeMapRasterizer: samplesPerSide must be positive, got " + samplesPerSide);
        }
        int[] biomeOfCell = biomeIndexOfEveryCell(grid, pattern, biomeOrder);
        int[] biomeIndices = new int[samplesPerSide * samplesPerSide];
        int[] cells = new int[samplesPerSide * samplesPerSide];

        double tileAx = geometry.tileVectorAX();
        double tileAy = geometry.tileVectorAY();
        double tileBx = geometry.tileVectorBX();
        double tileBy = geometry.tileVectorBY();
        IntStream.range(0, samplesPerSide).parallel().forEach(row -> {
            double[] displacement = new double[2];
            double v = (row + 0.5) / samplesPerSide;
            for (int column = 0; column < samplesPerSide; column++) {
                double u = (column + 0.5) / samplesPerSide;
                double x = u * tileAx + v * tileBx;
                double y = u * tileAy + v * tileBy;
                warp.displace(x, y, displacement);
                ToroidalPos cell = geometry.cellAt(x + displacement[0], y + displacement[1]);
                int cellIndex = cell.r() * grid.width() + cell.q();
                cells[row * samplesPerSide + column] = cellIndex;
                biomeIndices[row * samplesPerSide + column] = biomeOfCell[cellIndex];
            }
        });

        double blocksPerSample = Math.hypot(tileAx, tileAy) / samplesPerSide;
        return new BiomeMap(samplesPerSide, biomeIndices, cells, biomeOrder, geometry.tileAreaBlocks2() / ((double) samplesPerSide * samplesPerSide), blocksPerSample);
    }

    private static int[] biomeIndexOfEveryCell(ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern, List<BiomeId> biomeOrder) {
        int[] biomeOfCell = new int[grid.cellCount()];
        for (int r = 0; r < grid.height(); r++) {
            for (int q = 0; q < grid.width(); q++) {
                BiomeId biome = pattern.get(new ToroidalPos(q, r));
                if (biome == null) {
                    throw new IllegalArgumentException("BiomeMapRasterizer: the pattern has no biome for cell (" + q + "," + r + ")");
                }
                int index = biomeOrder.indexOf(biome);
                if (index < 0) {
                    throw new IllegalArgumentException("BiomeMapRasterizer: " + biome + " is used by the pattern but missing from the biome order");
                }
                biomeOfCell[r * grid.width() + q] = index;
            }
        }
        return biomeOfCell;
    }
}
