package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;

/**
 * A biome for every sample of a square lattice laid over one tile (2026-09-20). Sample
 * {@code (i, j)} sits at {@code (i + 0.5) / size} of tile vector A plus {@code (j + 0.5) / size} of
 * tile vector B, so the lattice is as skewed as the tile: each sample has SIX nearest neighbors,
 * {@code (+-1, 0)}, {@code (0, +-1)} and {@code (+1, -1)}, {@code (-1, +1)}, exactly like the hex
 * cells, and indexing wraps around the tile in both directions.
 *
 * <p>Besides the biome, each sample remembers which pattern cell it was taken from, so a region of
 * the map can be traced back to the cluster of cells it came from.
 */
final class BiomeMap {

    private final int size;
    private final int[] biomeIndices;
    private final int[] cells;
    private final List<BiomeId> biomes;
    private final double sampleAreaBlocks2;
    private final double blocksPerSample;

    BiomeMap(int size, int[] biomeIndices, int[] cells, List<BiomeId> biomes, double sampleAreaBlocks2, double blocksPerSample) {
        this.size = size;
        this.biomeIndices = biomeIndices;
        this.cells = cells;
        this.biomes = biomes;
        this.sampleAreaBlocks2 = sampleAreaBlocks2;
        this.blocksPerSample = blocksPerSample;
    }

    int size() {
        return size;
    }

    List<BiomeId> biomes() {
        return biomes;
    }

    /** The map's raw biome indices, row by row: sample (i, j) is at {@code j * size + i}. */
    int[] biomeIndices() {
        return biomeIndices;
    }

    /** The raw pattern-cell indices (row * width + column of the cell), laid out like {@link #biomeIndices()}. */
    int[] cells() {
        return cells;
    }

    double sampleAreaBlocks2() {
        return sampleAreaBlocks2;
    }

    /** Distance in blocks between neighboring samples along a tile vector. */
    double blocksPerSample() {
        return blocksPerSample;
    }

    int biomeAt(int i, int j) {
        return biomeIndices[indexOf(i, j)];
    }

    int cellAt(int i, int j) {
        return cells[indexOf(i, j)];
    }

    /** Position of sample (i, j) in the raw arrays, wrapped around the tile. */
    int indexOf(int i, int j) {
        return Math.floorMod(j, size) * size + Math.floorMod(i, size);
    }
}
