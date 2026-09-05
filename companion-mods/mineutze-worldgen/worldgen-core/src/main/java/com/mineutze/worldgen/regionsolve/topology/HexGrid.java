package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, unjittered pointy-top hex lattice, axial-coordinate
 * addressed, anchored to a fixed global origin (block (0,0)) — never to a
 * query window, mirroring {@link SeedGrid}'s own window-independence stance
 * (same physical cell must always produce the same result, no matter which
 * window is querying).
 *
 * <p>Unlike {@code SeedGrid}, a hex CENTER is never jittered: this is a pure
 * closed-form regular lattice, so two overlapping windows agree exactly, not
 * just statistically — the whole reason a literal hex lattice (over a
 * Voronoi-dual alternative) was chosen was to make vertex-degree-3 and
 * face-degree-6 exact by construction, and jittering centers would reopen
 * that same empirical uncertainty. All organic variety comes from edge
 * displacement downstream, not from this lattice.
 */
public final class HexGrid {

    private HexGrid() {
    }

    /** Axial hex coordinate — see {@link #centerOf}. */
    public record AxialPos(int q, int r) {
    }

    /**
     * Pointy-top axial-to-cartesian conversion, size = center-to-corner
     * radius in blocks.
     */
    public static Point2D centerOf(AxialPos pos, double sizeBlocks) {
        double x = sizeBlocks * Math.sqrt(3) * (pos.q() + pos.r() / 2.0);
        double z = sizeBlocks * 1.5 * pos.r();
        return new Point2D(x, z);
    }

    /**
     * Every axial position whose center falls in the half-open box
     * {@code [minX, maxX) x [minZ, maxZ)} (block coordinates) — same
     * scan-a-superset-then-filter-exactly idiom {@link SeedGrid#seedsIn} uses,
     * with one extra ring of margin on the r-derived q range since the axial
     * lattice is skewed (a fixed r doesn't bound q independently of it).
     */
    public static List<AxialPos> axialPositionsIn(double sizeBlocks, int minX, int minZ, int maxX, int maxZ) {
        requireValidBox(sizeBlocks, minX, minZ, maxX, maxZ);

        double rExactAtMinZ = minZ / (1.5 * sizeBlocks);
        double rExactAtMaxZ = maxZ / (1.5 * sizeBlocks);
        int rMin = (int) Math.floor(Math.min(rExactAtMinZ, rExactAtMaxZ)) - 1;
        int rMax = (int) Math.ceil(Math.max(rExactAtMinZ, rExactAtMaxZ)) + 1;

        List<AxialPos> positions = new ArrayList<>();
        for (int r = rMin; r <= rMax; r++) {
            double qExactAtMinX = minX / (sizeBlocks * Math.sqrt(3)) - r / 2.0;
            double qExactAtMaxX = maxX / (sizeBlocks * Math.sqrt(3)) - r / 2.0;
            int qMin = (int) Math.floor(Math.min(qExactAtMinX, qExactAtMaxX)) - 1;
            int qMax = (int) Math.ceil(Math.max(qExactAtMinX, qExactAtMaxX)) + 1;
            for (int q = qMin; q <= qMax; q++) {
                AxialPos pos = new AxialPos(q, r);
                if (isInsideBox(centerOf(pos, sizeBlocks), minX, minZ, maxX, maxZ)) {
                    positions.add(pos);
                }
            }
        }
        return positions;
    }

    private static void requireValidBox(double sizeBlocks, int minX, int minZ, int maxX, int maxZ) {
        if (sizeBlocks <= 0) {
            throw new IllegalArgumentException("sizeBlocks must be positive, got " + sizeBlocks);
        }
        if (maxX <= minX || maxZ <= minZ) {
            throw new IllegalArgumentException("box must have positive area, got [" + minX + "," + maxX + ") x [" + minZ + "," + maxZ + ")");
        }
    }

    private static boolean isInsideBox(Point2D point, int minX, int minZ, int maxX, int maxZ) {
        return point.x() >= minX && point.x() < maxX && point.z() >= minZ && point.z() < maxZ;
    }

    /**
     * The inverse of {@link #centerOf}: the axial position whose UNDISPLACED center is
     * closest to {@code point} — the standard "cube coordinate rounding" hex-grid
     * algorithm (round each of the 3 cube coordinates independently, then fix whichever
     * one drifted furthest from its exact fractional value so {@code q+r+s=0} still
     * holds exactly).
     *
     * <p>Only a STARTING candidate for "which real (possibly wavy-displaced) face covers
     * this point" — never authoritative on its own. Real displacement can shift a face's
     * true boundary away from its own undisplaced footprint, so a caller must always
     * verify against the REAL committed boundary (or fall back to solving) rather than
     * trust this result directly — see {@code SeedMeshCache}'s own doc for how this is
     * actually used.
     */
    public static AxialPos nearestAxialPos(Point2D point, double sizeBlocks) {
        double rFrac = (2.0 / 3.0) * point.z() / sizeBlocks;
        double qFrac = point.x() / (sizeBlocks * Math.sqrt(3)) - rFrac / 2.0;
        double sFrac = -qFrac - rFrac;

        long q = Math.round(qFrac);
        long r = Math.round(rFrac);
        long s = Math.round(sFrac);

        double qDiff = Math.abs(q - qFrac);
        double rDiff = Math.abs(r - rFrac);
        double sDiff = Math.abs(s - sFrac);

        if (qDiff > rDiff && qDiff > sDiff) {
            q = -r - s;
        } else if (rDiff > sDiff) {
            r = -q - s;
        }
        // else s is the odd one out - discarded, only q and r are ever stored (s = -q-r is implicit)

        return new AxialPos((int) q, (int) r);
    }
}
