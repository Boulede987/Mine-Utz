package com.mineutze.worldgen.regionsolve.topology.mesh;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, unjittered pointy-top hex lattice, axial-coordinate addressed, anchored
 * to a fixed global origin (block (0,0)) — never a query window, mirroring {@link
 * SeedGrid}'s window-independence stance (same physical cell always produces the same
 * result, regardless of which window queries).
 *
 * <p>Unlike {@code SeedGrid}, a hex CENTER is never jittered: pure closed-form regular
 * lattice, so two overlapping windows agree exactly, not just statistically — the whole
 * reason a literal hex lattice (over a Voronoi-dual alternative) was chosen was to make
 * vertex-degree-3 and face-degree-6 exact by construction; jittering centers would reopen
 * that empirical uncertainty. All organic variety comes from edge displacement downstream,
 * not this lattice.
 */
public final class HexGrid {

    /** A pointy-top hex's flat-to-flat width, in units of {@code sizeBlocks} - every axial-to-cartesian (or back) conversion derives from this one geometric ratio, never recomputed independently per call site. */
    private static final double HEX_WIDTH_FACTOR = Math.sqrt(3);

    /** How far {@code z} advances per {@code r} step, in units of {@code sizeBlocks} (see {@link #centerOf}) - {@link #nearestAxialPos}'s {@code r} reconstruction uses this factor's algebraic INVERSE, not an independently-chosen number, so both conversions stay provably each other's inverse, not two magic numbers that merely happen to agree today. */
    private static final double HEX_ROW_SPACING_FACTOR = 1.5;

    /** How much of a full {@code r} step feeds into {@code q}'s cartesian position - the axial lattice's skew factor (see {@link #centerOf}/{@link #nearestAxialPos}, both directions of the same relationship). */
    private static final double AXIAL_SKEW_FACTOR = 0.5;

    private HexGrid() {
    }

    /** Axial hex coordinate — see {@link #centerOf}. */
    public record AxialPos(int q, int r) {
    }

    /**
     * Pointy-top axial-to-cartesian conversion, size = center-to-corner
     * radius in blocks.
     */
    public static Point2D centerOf(AxialPos axialPos, double sizeBlocks) {
        double x = sizeBlocks * HEX_WIDTH_FACTOR * (axialPos.q() + axialPos.r() * AXIAL_SKEW_FACTOR);
        double z = sizeBlocks * HEX_ROW_SPACING_FACTOR * axialPos.r();
        return new Point2D(x, z);
    }

    /**
     * Every axial position whose center falls in the half-open box {@code [minX, maxX) x
     * [minZ, maxZ)} (block coords) — same scan-a-superset-then-filter-exactly idiom {@link
     * SeedGrid#seedsIn} uses, with one extra ring of margin on the r-derived q range since
     * the axial lattice is skewed (a fixed r doesn't bound q independently of it).
     */
    public static List<AxialPos> axialPositionsIn(double sizeBlocks, int minX, int minZ, int maxX, int maxZ) {
        requireValidBox(sizeBlocks, minX, minZ, maxX, maxZ);

        double rExactAtMinZ = minZ / (HEX_ROW_SPACING_FACTOR * sizeBlocks);
        double rExactAtMaxZ = maxZ / (HEX_ROW_SPACING_FACTOR * sizeBlocks);
        int rMin = (int) Math.floor(Math.min(rExactAtMinZ, rExactAtMaxZ)) - 1;
        int rMax = (int) Math.ceil(Math.max(rExactAtMinZ, rExactAtMaxZ)) + 1;

        List<AxialPos> positions = new ArrayList<>();
        for (int r = rMin; r <= rMax; r++) {
            double qExactAtMinX = minX / (sizeBlocks * HEX_WIDTH_FACTOR) - r * AXIAL_SKEW_FACTOR;
            double qExactAtMaxX = maxX / (sizeBlocks * HEX_WIDTH_FACTOR) - r * AXIAL_SKEW_FACTOR;
            int qMin = (int) Math.floor(Math.min(qExactAtMinX, qExactAtMaxX)) - 1;
            int qMax = (int) Math.ceil(Math.max(qExactAtMinX, qExactAtMaxX)) + 1;
            for (int q = qMin; q <= qMax; q++) {
                AxialPos axialPos = new AxialPos(q, r);
                if (isInsideBox(centerOf(axialPos, sizeBlocks), minX, minZ, maxX, maxZ)) {
                    positions.add(axialPos);
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
     * Inverse of {@link #centerOf}: axial position whose UNDISPLACED center is closest to
     * {@code point} — standard "cube coordinate rounding" hex-grid algorithm (round each
     * of the 3 cube coordinates independently, then fix whichever drifted furthest from
     * its exact fractional value so {@code q+r+s=0} still holds exactly).
     *
     * <p>Only a STARTING candidate for "which real (possibly wavy-displaced) face covers
     * this point" — never authoritative alone. Real displacement can shift a face's true
     * boundary away from its undisplaced footprint, so a caller must always verify against
     * the REAL committed boundary (or fall back to solving), not trust this directly — see
     * {@code SeedMeshCache} for how this is actually used.
     */
    public static AxialPos nearestAxialPos(Point2D point, double sizeBlocks) {
        double rFractional = (1.0 / HEX_ROW_SPACING_FACTOR) * point.z() / sizeBlocks;
        double qFractional = point.x() / (sizeBlocks * HEX_WIDTH_FACTOR) - rFractional * AXIAL_SKEW_FACTOR;
        double sFractional = -qFractional - rFractional;

        long q = Math.round(qFractional);
        long r = Math.round(rFractional);
        long s = Math.round(sFractional);

        double qRoundingError = Math.abs(q - qFractional);
        double rRoundingError = Math.abs(r - rFractional);
        double sRoundingError = Math.abs(s - sFractional);

        if (qRoundingError > rRoundingError && qRoundingError > sRoundingError) {
            q = -r - s;
        } else if (rRoundingError > sRoundingError) {
            r = -q - s;
        }
        // else s is the odd one out - discarded, only q and r are ever stored (s = -q-r is implicit)

        return new AxialPos((int) q, (int) r);
    }
}
