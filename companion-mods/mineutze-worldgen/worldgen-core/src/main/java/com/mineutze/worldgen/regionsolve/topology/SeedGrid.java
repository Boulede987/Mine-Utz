package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic seed placement with NO notion of "region"/batch/solve unit — jittered grid
 * anchored to fixed global origin (multiples of {@code spacingBlocks} from block (0,0)),
 * same idea vanilla structure placement and Worley noise use: each grid cell owns at most
 * one point, jittered within its cell; point's existence/position depend only on {@code
 * (worldSeed, spacingBlocks, that cell)} — never which window is querying.
 *
 * <p><b>Window-independence is the whole point, not implementation detail</b> — replaces
 * deleted {@code SeedGraphBuilder}'s dependency on whole-region batch scatter ({@code
 * SeedPlacer}, deleted alongside it, anchored its grid to each region's corner, so the
 * SAME physical location could get different seed placement depending which region "owned"
 * it — see doc/DESIGN.md's "no reseeding, we make it work" stance). Two overlapping windows
 * must agree on every seed both contain — see {@code SeedGridTest}.
 */
public final class SeedGrid {

    private static final long JITTER_SALT = 0x5EED_9A31L;

    /** Classic hash-spreading multiplier (same convention as {@code java.util.Objects.hash}, and {@code EdgeDisplacement}/{@code WeightedVertexPlacement}'s own {@code HASH_MULTIPLIER}). */
    private static final long HASH_MULTIPLIER = 31;

    private SeedGrid() {
    }

    /**
     * Every seed whose jittered position falls in half-open box {@code [minX, maxX) x
     * [minZ, maxZ)} (block coords). Scans one extra cell margin every side so a grid cell
     * just outside the box, whose jitter could still land inside, is never missed.
     */
    public static List<Point2D> seedsIn(long worldSeed, int spacingBlocks, int minX, int minZ, int maxX, int maxZ) {
        requireValidBox(spacingBlocks, minX, minZ, maxX, maxZ);

        int scanMinX = floorToGrid(minX, spacingBlocks) - spacingBlocks;
        int scanMinZ = floorToGrid(minZ, spacingBlocks) - spacingBlocks;

        List<Point2D> seeds = new ArrayList<>();
        for (int gridX = scanMinX; gridX < maxX + spacingBlocks; gridX += spacingBlocks) {
            for (int gridZ = scanMinZ; gridZ < maxZ + spacingBlocks; gridZ += spacingBlocks) {
                Point2D jittered = jitteredPointForCell(worldSeed, gridX, gridZ, spacingBlocks);
                if (isInsideBox(jittered, minX, minZ, maxX, maxZ)) {
                    seeds.add(jittered);
                }
            }
        }
        return seeds;
    }

    private static void requireValidBox(int spacingBlocks, int minX, int minZ, int maxX, int maxZ) {
        if (spacingBlocks <= 0) {
            throw new IllegalArgumentException("spacingBlocks must be positive, got " + spacingBlocks);
        }
        if (maxX <= minX || maxZ <= minZ) {
            throw new IllegalArgumentException("box must have positive area, got [" + minX + "," + maxX + ") x [" + minZ + "," + maxZ + ")");
        }
    }

    private static int floorToGrid(int value, int spacingBlocks) {
        return Math.floorDiv(value, spacingBlocks) * spacingBlocks;
    }

    /** Jittered point for one grid cell — same {@code (worldSeed, gridX, gridZ, spacingBlocks)} always produces exact same point regardless of which window queries (see class doc). */
    private static Point2D jitteredPointForCell(long worldSeed, int gridX, int gridZ, int spacingBlocks) {
        Random jitterRandom = new Random(jitterSeed(worldSeed, gridX, gridZ));
        int jitteredX = gridX + jitterRandom.nextInt(spacingBlocks);
        int jitteredZ = gridZ + jitterRandom.nextInt(spacingBlocks);
        return new Point2D(jitteredX, jitteredZ);
    }

    private static boolean isInsideBox(Point2D point, int minX, int minZ, int maxX, int maxZ) {
        return point.x() >= minX && point.x() < maxX && point.z() >= minZ && point.z() < maxZ;
    }

    /** Same hash idiom the rest of this codebase uses for deterministic-per-input noise — never a persisted {@link Random} stream. */
    private static long jitterSeed(long worldSeed, int gridX, int gridZ) {
        long seed = worldSeed ^ JITTER_SALT;
        seed = seed * HASH_MULTIPLIER + gridX;
        seed = seed * HASH_MULTIPLIER + gridZ;
        return seed;
    }
}
