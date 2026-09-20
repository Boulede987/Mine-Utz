package com.mineutze.worldgen.plan;

import java.util.List;

/**
 * Everything a {@link WorldBiomePlan} needs to be restored exactly, as plain values so a save file
 * can hold it (2026-09-20). The raster is the source of truth: once a world has one, nothing about it
 * is ever recomputed. The other fields say how it was made, for debugging and for noticing that the
 * catalog it was made from has changed since.
 *
 * <p>{@code raster} holds, row by row, the index into {@code palette} of the biome of each sample
 * ({@code rasterSize} x {@code rasterSize} samples over one tile); {@code palette} lists the biomes
 * as {@code namespace:path}, so a catalog reordering cannot silently reinterpret the indices.
 */
public record PlanSnapshot(
    int schemaVersion, long worldSeed, String catalogFingerprint, int gridWidth, int gridHeight, double atomWidthBlocks, double warpStrength, int patternAttempts,
    int strengthsTried, List<String> palette, int rasterSize, byte[] raster
) {
}
