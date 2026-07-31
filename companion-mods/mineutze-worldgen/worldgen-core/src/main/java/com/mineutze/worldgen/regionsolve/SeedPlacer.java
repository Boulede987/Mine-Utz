package com.mineutze.worldgen.regionsolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic seed placement for one solve-region: a jittered grid, which
 * approximates blue-noise/Poisson-disc spacing cheaply. Same
 * (worldSeed, region, spacing) always produces the same seeds.
 */
public final class SeedPlacer {

    private static final long JITTER_SALT = 0x5EED_9A31L;

    private SeedPlacer() {
    }

    public static List<MacroCellPos> placeSeeds(RegionPos region, MacroGridConfig grid, long worldSeed, int seedSpacingBlocks) {
        int spacingCells = Math.max(1, seedSpacingBlocks / grid.macroCellSizeBlocks());

        int minX = region.x() * grid.regionSizeCellsPerSide();
        int minZ = region.z() * grid.regionSizeCellsPerSide();
        int sideLength = grid.regionSizeCellsPerSide();

        List<MacroCellPos> seeds = new ArrayList<>();
        for (int gridX = minX; gridX < minX + sideLength; gridX += spacingCells) {
            for (int gridZ = minZ; gridZ < minZ + sideLength; gridZ += spacingCells) {
                Random jitterRandom = new Random(jitterSeed(worldSeed, gridX, gridZ));
                int jitteredX = gridX + jitterRandom.nextInt(spacingCells);
                int jitteredZ = gridZ + jitterRandom.nextInt(spacingCells);
                MacroCellPos candidate = clampToRegion(jitteredX, jitteredZ, minX, minZ, sideLength);
                seeds.add(candidate);
            }
        }

        return seeds.stream()
            .distinct()
            .sorted((a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()))
            .toList();
    }

    private static MacroCellPos clampToRegion(int x, int z, int minX, int minZ, int sideLength) {
        int clampedX = Math.min(Math.max(x, minX), minX + sideLength - 1);
        int clampedZ = Math.min(Math.max(z, minZ), minZ + sideLength - 1);
        return new MacroCellPos(clampedX, clampedZ);
    }

    private static long jitterSeed(long worldSeed, int gridX, int gridZ) {
        long seed = worldSeed ^ JITTER_SALT;
        seed = seed * 31 + gridX;
        seed = seed * 31 + gridZ;
        return seed;
    }
}
