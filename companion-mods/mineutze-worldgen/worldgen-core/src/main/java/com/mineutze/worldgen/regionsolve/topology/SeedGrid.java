package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic seed placement with NO notion of "region" or any other
 * batch/solve unit — a jittered grid anchored to a fixed global origin
 * (multiples of {@code spacingBlocks} from block (0,0)), same idea vanilla
 * structure placement and Worley noise both use: each grid cell owns at
 * most one point, jittered within its own cell, and that point's existence
 * and position depend only on {@code (worldSeed, spacingBlocks, that one
 * cell)} — never on which window a caller happens to be querying through.
 *
 * <p><b>This window-independence is the whole point, not an implementation
 * detail</b> — replaces the deleted {@code SeedGraphBuilder}'s dependency on
 * whole-region batch scatter ({@code SeedPlacer}, deleted alongside it,
 * anchored its own grid to each region's own corner, meaning the SAME
 * physical location could get different seed placement depending which
 * region "owned" it — see doc/DESIGN.md's "no reseeding, we make it work"
 * stance and this session's design discussion on why region-batch placement
 * was dropped). Querying any two overlapping windows must agree on every
 * seed both windows contain — see {@code SeedGridTest} for the assertion
 * that keeps this true.
 */
public final class SeedGrid {

    private static final long JITTER_SALT = 0x5EED_9A31L;

    private SeedGrid() {
    }

    /**
     * Every seed whose jittered position falls in the half-open box
     * {@code [minX, maxX) x [minZ, maxZ)} (block coordinates). Scans one
     * extra cell of margin on every side so a grid cell just outside the
     * box, whose jitter could still land inside it, is never missed.
     */
    public static List<Point2D> seedsIn(long worldSeed, int spacingBlocks, int minX, int minZ, int maxX, int maxZ) {
        if (spacingBlocks <= 0) {
            throw new IllegalArgumentException("spacingBlocks must be positive, got " + spacingBlocks);
        }
        if (maxX <= minX || maxZ <= minZ) {
            throw new IllegalArgumentException("box must have positive area, got [" + minX + "," + maxX + ") x [" + minZ + "," + maxZ + ")");
        }

        int scanMinX = floorToGrid(minX, spacingBlocks) - spacingBlocks;
        int scanMinZ = floorToGrid(minZ, spacingBlocks) - spacingBlocks;

        List<Point2D> seeds = new ArrayList<>();
        for (int gridX = scanMinX; gridX < maxX + spacingBlocks; gridX += spacingBlocks) {
            for (int gridZ = scanMinZ; gridZ < maxZ + spacingBlocks; gridZ += spacingBlocks) {
                Random jitterRandom = new Random(jitterSeed(worldSeed, gridX, gridZ));
                int jitteredX = gridX + jitterRandom.nextInt(spacingBlocks);
                int jitteredZ = gridZ + jitterRandom.nextInt(spacingBlocks);
                if (jitteredX >= minX && jitteredX < maxX && jitteredZ >= minZ && jitteredZ < maxZ) {
                    seeds.add(new Point2D(jitteredX, jitteredZ));
                }
            }
        }
        return seeds;
    }

    private static int floorToGrid(int value, int spacingBlocks) {
        return Math.floorDiv(value, spacingBlocks) * spacingBlocks;
    }

    /** Same hash idiom the rest of this codebase uses for deterministic-per-input noise — never a persisted {@link Random} stream. */
    private static long jitterSeed(long worldSeed, int gridX, int gridZ) {
        long seed = worldSeed ^ JITTER_SALT;
        seed = seed * 31 + gridX;
        seed = seed * 31 + gridZ;
        return seed;
    }
}
