package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Displaces a mesh edge's straight chord into a wavy polyline: several
 * independently-hashed interior points, each offset perpendicular to the
 * chord, rather than one single midpoint bump — reuses {@link
 * com.mineutze.worldgen.regionsolve.MacroGridConfig}'s already-proven lesson
 * that a single bump/sine reads as unnatural no matter how tuned (its own
 * warp field doc makes this exact argument for the region-boundary case;
 * same reasoning applies here at the per-edge scale).
 *
 * <p><b>Hashed by ENDPOINT COORDINATES, not by any index identity.</b> Two
 * independently-built meshes (e.g. two neighboring regions' own separate
 * {@link HexMeshBuilder#build} calls, each starting its own vertex pool from
 * index 0) can never agree on an index-based edge identity — but {@link
 * HexGrid}'s closed-form geometry guarantees the SAME physical corner point
 * gets the SAME cartesian coordinate regardless of which window generated
 * it. Hashing the two endpoints' own positions (canonically ordered so
 * either call direction agrees) is what makes two independently-built
 * meshes compute the bit-identical curve for a shared physical edge with no
 * live coordination between them — this is the actual mechanism watertight
 * cross-mesh seams depend on, not a property of {@link NodeEdge} indices.
 */
public final class EdgeDisplacement {

    private static final long SALT = Salt.of("EdgeDisplacement.wiggle");
    private static final int INTERIOR_POINT_COUNT = 3;

    private EdgeDisplacement() {
    }

    /**
     * The chord from {@code a} to {@code b}, displaced into a
     * {@code (2 + INTERIOR_POINT_COUNT)}-point polyline starting at {@code a}
     * and ending at {@code b} (in that order, whichever physical endpoint
     * either one is). Every interior point is offset perpendicular to the
     * chord by {@code biasBlocks} (a systematic, signed nudge - see below)
     * plus an independently-hashed random wiggle of at most
     * {@code ±wiggleAmplitudeBlocks} - the two are separate knobs: wiggle is
     * organic texture with no net direction, bias is a deliberate net push
     * to one side (e.g. {@link FaceSizeFeasibility}'s per-edge area
     * correction).
     *
     * <p>{@code biasBlocks} is signed in a fixed CANONICAL direction,
     * independent of which order {@code a}/{@code b} are passed in: positive
     * means "toward the side the perpendicular {@code (-dz, dx)} of
     * {@code (canonicalUpper - canonicalLower)} points to", where
     * {@code canonicalLower}/{@code canonicalUpper} are {@code a}/{@code b}
     * sorted lexicographically. A caller determining which physical side
     * that is for a given face (as {@link FaceSizeFeasibility} does, via a
     * cross-product sign test against that face's own centroid) gets a
     * bias value that means the same physical thing regardless of which
     * order it happened to look up the edge's endpoints in.
     *
     * <p>Order-independent otherwise too: calling with {@code (a, b)} or
     * {@code (b, a)} produces the exact same set of interior point
     * POSITIONS, just walked in the opposite direction to match whichever
     * endpoint order was requested - see this class's own doc for why that
     * is the actual mechanism cross-mesh watertightness depends on.
     */
    public static List<Point2D> displacedPolyline(Point2D a, Point2D b, long worldSeed, double wiggleAmplitudeBlocks, double biasBlocks) {
        if (wiggleAmplitudeBlocks < 0) {
            throw new IllegalArgumentException("wiggleAmplitudeBlocks must be non-negative, got " + wiggleAmplitudeBlocks);
        }
        if (a.equals(b)) {
            throw new IllegalArgumentException("edge endpoints must be distinct, both were " + a);
        }

        boolean reversed = !isCanonicallyFirst(a, b);
        Point2D lower = reversed ? b : a;
        Point2D upper = reversed ? a : b;

        List<Point2D> canonical = canonicalPolyline(lower, upper, worldSeed, wiggleAmplitudeBlocks, biasBlocks);
        if (!reversed) {
            return canonical;
        }
        List<Point2D> result = new ArrayList<>(canonical);
        Collections.reverse(result);
        return result;
    }

    private static boolean isCanonicallyFirst(Point2D a, Point2D b) {
        return a.x() != b.x() ? a.x() < b.x() : a.z() < b.z();
    }

    private static List<Point2D> canonicalPolyline(Point2D lower, Point2D upper, long worldSeed, double wiggleAmplitudeBlocks, double biasBlocks) {
        double dx = upper.x() - lower.x();
        double dz = upper.z() - lower.z();
        double length = Math.hypot(dx, dz);
        double perpX = -dz / length;
        double perpZ = dx / length;

        List<Point2D> points = new ArrayList<>(INTERIOR_POINT_COUNT + 2);
        points.add(lower);
        for (int i = 1; i <= INTERIOR_POINT_COUNT; i++) {
            double t = (double) i / (INTERIOR_POINT_COUNT + 1);
            double baseX = lower.x() + dx * t;
            double baseZ = lower.z() + dz * t;
            double offset = biasBlocks + wiggleAmplitudeBlocks * signedUnitOffset(lower, upper, worldSeed, i);
            points.add(new Point2D(baseX + perpX * offset, baseZ + perpZ * offset));
        }
        points.add(upper);
        return points;
    }

    /** Deterministic value in {@code [-1, 1)} for one interior point - same hash idiom the rest of this codebase uses for deterministic-per-input noise, never a persisted {@link java.util.Random} stream. */
    private static double signedUnitOffset(Point2D lower, Point2D upper, long worldSeed, int pointIndex) {
        long seed = worldSeed ^ SALT;
        seed = seed * 31 + Double.doubleToLongBits(lower.x());
        seed = seed * 31 + Double.doubleToLongBits(lower.z());
        seed = seed * 31 + Double.doubleToLongBits(upper.x());
        seed = seed * 31 + Double.doubleToLongBits(upper.z());
        seed = seed * 31 + pointIndex;
        long nonNegative = (seed ^ (seed >>> 32)) & 0xFFFF_FFFFL;
        return (double) nonNegative / 0xFFFF_FFFFL * 2.0 - 1.0;
    }
}
