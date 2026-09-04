package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
 * Still true when the endpoints are {@link WeightedVertexPlacement}'s own
 * computed positions, not raw hex vertices — both regions independently
 * compute the identical radical-center point for a shared vertex from the
 * same face weights, so the coordinates still agree with no coordination.
 *
 * <p>Wiggle only, no separate bias knob — macro-scale sizing now lives
 * entirely in WHERE the two endpoints are ({@link WeightedVertexPlacement}),
 * not in a mid-edge additive term (formerly this method's own {@code
 * biasBlocks} parameter, removed alongside {@code FaceSizeFeasibility}).
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
     * chord by an independently-hashed random wiggle of at most
     * {@code ±wiggleAmplitudeBlocks} — organic texture with no net
     * direction.
     *
     * <p>Order-independent: calling with {@code (a, b)} or {@code (b, a)}
     * produces the exact same set of interior point POSITIONS, just walked
     * in the opposite direction to match whichever endpoint order was
     * requested — see this class's own doc for why that is the actual
     * mechanism cross-mesh watertightness depends on.
     */
    public static List<Point2D> displacedPolyline(Point2D a, Point2D b, long worldSeed, double wiggleAmplitudeBlocks) {
        if (wiggleAmplitudeBlocks < 0) {
            throw new IllegalArgumentException("wiggleAmplitudeBlocks must be non-negative, got " + wiggleAmplitudeBlocks);
        }
        if (a.equals(b)) {
            throw new IllegalArgumentException("edge endpoints must be distinct, both were " + a);
        }

        boolean reversed = !isCanonicallyFirst(a, b);
        Point2D lower = reversed ? b : a;
        Point2D upper = reversed ? a : b;

        List<Point2D> canonical = canonicalPolyline(lower, upper, worldSeed, wiggleAmplitudeBlocks);
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

    private static List<Point2D> canonicalPolyline(Point2D lower, Point2D upper, long worldSeed, double wiggleAmplitudeBlocks) {
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
            double offset = wiggleAmplitudeBlocks * signedUnitOffset(lower, upper, worldSeed, i);
            points.add(new Point2D(baseX + perpX * offset, baseZ + perpZ * offset));
        }
        points.add(upper);
        return points;
    }

    /**
     * Deterministic value in {@code [-1, 1)} for one interior point - a FRESH {@link
     * Random} per call, never a persisted stream (matches {@link SeedGrid}'s own idiom).
     *
     * <p><b>Real bug, found and fixed 2026-09-04:</b> the original version folded {@code
     * pointIndex} into the seed via one final {@code seed = seed * 31 + pointIndex} step,
     * then read the result directly (XOR-folded, no further mixing) - since that's the
     * LAST operation before reading, a change of just 1-3 in {@code pointIndex} only ever
     * perturbs the seed's lowest few bits, with no subsequent avalanche step to spread
     * that into the bits the final value actually reads from. Measured directly: the 3
     * "independent" interior points on a real edge came out agreeing to 9 decimal places
     * - not independent at all, just one offset value read 3 times, collapsing the whole
     * multi-point design into a single smooth bow per edge (exactly the "one bump reads
     * as fake no matter how tuned" failure {@link com.mineutze.worldgen.regionsolve.MacroGridConfig}'s
     * own doc already diagnosed for a different mechanism).
     *
     * <p>Fixed by discarding {@link Random}'s own FIRST draw before using its second: one
     * fresh {@code nextDouble()} call from a seed differing only in its lowest bits was
     * measured to still be too similar (agreeing to ~3 decimal places, better but not
     * genuinely independent) - {@link Random}'s LCG needs to actually run its
     * multiply-and-add step at least once on already-mixed state before the tiny
     * {@code pointIndex} perturbation has fully diffused into the high bits the returned
     * value is read from. Verified directly: with one discarded draw, 5 consecutive
     * {@code pointIndex} values scatter across the whole range, no longer clustering.
     */
    private static double signedUnitOffset(Point2D lower, Point2D upper, long worldSeed, int pointIndex) {
        long seed = worldSeed ^ SALT;
        seed = seed * 31 + Double.doubleToLongBits(lower.x());
        seed = seed * 31 + Double.doubleToLongBits(lower.z());
        seed = seed * 31 + Double.doubleToLongBits(upper.x());
        seed = seed * 31 + Double.doubleToLongBits(upper.z());
        seed = seed * 31 + pointIndex;
        Random random = new Random(seed);
        random.nextDouble(); // discarded - see this method's own doc for why one draw isn't enough diffusion
        return random.nextDouble() * 2.0 - 1.0;
    }
}
