package com.mineutze.worldgen.plan;

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
        requirePositive(samplesPerSide);
        RealizedBiomeField field = new RealizedBiomeField(grid, pattern, geometry, warp, biomeOrder);
        int[] cells = new int[samplesPerSide * samplesPerSide];
        int[] biomeIndices = new int[samplesPerSide * samplesPerSide];
        sampleTile(field, geometry, samplesPerSide, cells, biomeIndices);

        double sampleAreaBlocks2 = geometry.tileAreaBlocks2() / ((double) samplesPerSide * samplesPerSide);
        return new BiomeMap(samplesPerSide, biomeIndices, cells, biomeOrder, sampleAreaBlocks2, blocksPerSample(geometry, samplesPerSide));
    }

    private static void requirePositive(int samplesPerSide) {
        if (samplesPerSide < 1) {
            throw new IllegalArgumentException("BiomeMapRasterizer: samplesPerSide must be positive, got " + samplesPerSide);
        }
    }

    /** Fills {@code cells}/{@code biomeIndices} (row-major, {@code samplesPerSide x samplesPerSide}) by asking {@code field} for the cell at each sample's centre, in tile-fraction coordinates {@code (u, v)}. */
    private static void sampleTile(RealizedBiomeField field, HexTorusGeometry geometry, int samplesPerSide, int[] cells, int[] biomeIndices) {
        double tileAx = geometry.tileVectorAX();
        double tileAy = geometry.tileVectorAY();
        double tileBx = geometry.tileVectorBX();
        double tileBy = geometry.tileVectorBY();
        IntStream.range(0, samplesPerSide).parallel().forEach(row -> {
            double v = (row + 0.5) / samplesPerSide;
            for (int column = 0; column < samplesPerSide; column++) {
                double u = (column + 0.5) / samplesPerSide;
                int cellIndex = field.cellIndexAt(u * tileAx + v * tileBx, u * tileAy + v * tileBy);
                cells[row * samplesPerSide + column] = cellIndex;
                biomeIndices[row * samplesPerSide + column] = field.biomeOfCell(cellIndex);
            }
        });
    }

    private static double blocksPerSample(HexTorusGeometry geometry, int samplesPerSide) {
        double tileAx = geometry.tileVectorAX();
        double tileAy = geometry.tileVectorAY();
        return Math.sqrt(tileAx * tileAx + tileAy * tileAy) / samplesPerSide;
    }
}
