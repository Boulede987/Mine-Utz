package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Displaces mesh edge's straight chord into wavy polyline: several independently-hashed
 * interior points, offset perpendicular to chord, not one midpoint bump — reuses {@link
 * com.mineutze.worldgen.regionsolve.MacroGridConfig}'s lesson that single bump/sine reads
 * unnatural no matter how tuned (its warp field doc argues this for region-boundary case,
 * same reasoning here per-edge).
 *
 * <p><b>Hashed by ENDPOINT COORDINATES, not index identity.</b> Two independently-built
 * meshes (e.g. two neighboring regions' own {@link HexMeshBuilder#build} calls, each
 * starting its own vertex pool from index 0) can't agree on index-based edge identity —
 * but {@link HexGrid}'s closed-form geometry guarantees SAME physical corner point gets
 * SAME cartesian coordinate regardless of which window generated it. Hashing the two
 * endpoints' positions (canonically ordered so either call direction agrees) is what makes
 * two independently-built meshes compute bit-identical curve for a shared edge with no
 * live coordination — the actual mechanism watertight cross-mesh seams depend on, not a
 * property of {@link NodeEdge} indices. Still true when endpoints are {@link
 * WeightedVertexPlacement}'s computed positions, not raw hex vertices — both regions
 * independently compute the identical radical-center point for a shared vertex from the
 * same face weights, so coordinates agree with no coordination.
 *
 * <p><b>Wiggle only, no separate bias knob</b> — macro-scale sizing lives entirely in
 * WHERE the two endpoints are ({@link WeightedVertexPlacement}), not a mid-edge additive
 * term.
 *
 * <p><b>Amplitude derived from geometry, not a tuned fraction — by-construction safety
 * guarantee, not a checked one (2026-09-04 redesign).</b> Original version pushed each
 * interior point perpendicular to the chord by a flat {@code amplitude} parameter (fixed
 * fraction of edge length), nothing verifying the result stayed simple — large enough
 * amplitude could fold a face's boundary onto itself, producing the disconnected-
 * sliver/isolated-patch symptom this redesign prevents by construction, not checking
 * afterward (checking the full wavy boundary for self-intersection needs an O(n²) segment
 * scan plus a search for how far to back off — real cost this design avoids).
 *
 * <p>Core idea: a polygon is star-shaped w.r.t. a point when every boundary point's ANGLE
 * around that point strictly increases walking the boundary once — star-shaped implies
 * both simple (no self-crossing) and unified (one connected blob, no holes/pinched-off
 * islands), same guarantee covering both at once. This class NEVER perturbs a point's
 * angle from either owning face's center — only its RADIUS (via the perpendicular-to-chord
 * offset, decomposed into a fixed angle component and a moving radius component). Each
 * interior point's safe offset range is solved in closed form (one linear equation per
 * boundary — see {@link #crossingOffset}), not searched: the point where moving further
 * perpendicular crosses the angular bisector toward its own static neighbor (previous/next
 * point on the SAME edge, using UNPERTURBED positions, known before any point moves — no
 * ordering/coordination needed). A shared edge's two owning faces each compute this bound
 * from their own center independently, take the smaller — both derive it from the same
 * shared inputs (same edge, same hash), so they agree with no coordination, same trick as
 * this class's endpoint-hashing for cross-mesh watertightness.
 *
 * <p><b>Open assumption, not yet independently verified:</b> construction requires each
 * owning face's own STRAIGHT hexagon already star-shaped w.r.t. its own center — true by
 * construction for a raw, unclamped power-diagram cell (provably convex), but {@link
 * WeightedVertexPlacement}'s adaptive clamp has only ever proven its output SIMPLE, not
 * star-shaped, after scaling. Not automatically the same property.
 */
public final class EdgeDisplacement {

    private static final long SALT = Salt.of("EdgeDisplacement.wiggle");
    private static final long INTENSITY_SALT = Salt.of("EdgeDisplacement.edgeIntensity");

    /**
     * Floor on {@link #edgeIntensity}'s [MIN_EDGE_INTENSITY, 1.0) range - every edge keeps
     * SOME wiggle, but how much of its safe room it uses varies. Raised from 0.25 - that let
     * an unlucky edge draw near a quarter of its room, reading flat/calm not wavy; 0.6 keeps
     * real variety (~1.7x spread calmest to boldest) while "calm" never means "straight."
     */
    private static final double MIN_EDGE_INTENSITY = 0.6;
    private static final int INTERIOR_POINT_COUNT = 6;

    /**
     * Chaikin corner-cutting rounds on the finished polyline (see {@link #chaikinSmooth}) —
     * 2 turns a sharp zigzag into a gentle curve without exploding point count (each round
     * roughly doubles it: 5 points become 10, then ~19 after two rounds, cheap for a 6-edge
     * hex face).
     */
    private static final int SMOOTHING_ITERATIONS = 3;

    /** Standard open-curve Chaikin cut points - each segment replaced by two points at these fractions, endpoints kept exact (see {@link #chaikinSmooth} for why provably safe). */
    private static final double FIRST_CUT_T = 0.25;
    private static final double SECOND_CUT_T = 0.75;

    /**
     * Shrinks every closed-form safe range before use, so a point never lands EXACTLY on
     * its angular boundary (float error could tip it across) and two neighbors approaching
     * the same bisector from opposite sides never both reach it. Close to 1 on purpose —
     * closed-form range is already the real geometric limit, no heuristic headroom needed.
     */
    private static final double SAFETY_MARGIN = 0.9;

    /**
     * Supplementary cap: point never moves closer than this fraction of its OWN current
     * distance to a pole, regardless of the bisector-crossing formula (see {@link
     * #crossingOffset}'s "same side of the ray" check, which can leave NO limit at all in
     * some degenerate directions). Cheap, always applied. See DECISIONS.md 2026-09-04
     * "EdgeDisplacement.MAX_RADIUS_FRACTION" for the real bug this closes.
     */
    private static final double MAX_RADIUS_FRACTION = 0.5;

    /** Below this vector length, direction treated as degenerate (pole coincides with measured point) not divided-by-near-zero. Shared everywhere this class turns a point pair into a unit direction. */
    private static final double DEGENERATE_LENGTH_EPSILON_BLOCKS = 1e-9;

    /** Below this cross-product magnitude, two directions treated as parallel in {@link #crossingOffset} - moving along one never reaches a ray in the other's direction. Tighter than {@link #DEGENERATE_LENGTH_EPSILON_BLOCKS}: bounds a cross product (length²-ish), different quantity. */
    private static final double PARALLEL_EPSILON = 1e-12;

    /** Classic hash-spreading multiplier (same convention as {@code java.util.Objects.hash}), shared by every per-coordinate hash here. */
    private static final long HASH_MULTIPLIER = 31;

    /** {@link Random#nextDouble()} returns {@code [0, 1)}; remaps into this class's {@code [-1, 1)} signed-offset convention (see {@link #signedUnitOffset}). */
    private static final double UNIFORM_TO_SIGNED_SCALE = 2.0;
    private static final double UNIFORM_TO_SIGNED_OFFSET = 1.0;

    private EdgeDisplacement() {
    }

    /**
     * Chord from {@code a} to {@code b}, displaced into a {@code (2 + INTERIOR_POINT_COUNT)}
     * -point polyline starting at {@code a}, ending at {@code b}. Every interior point
     * offset perpendicular to the chord by an independently-hashed random amount, clamped
     * in closed form (see class doc) to what its two owning faces' geometry can safely
     * tolerate — {@code poleOne}/{@code poleTwo} are those faces' centers (straight
     * polygon's centroid, not necessarily {@link WeightedVertexPlacement}'s original
     * lattice point); {@code poleTwo} is {@code null} for a hull-touching edge (one owner).
     *
     * <p>Order-independent: {@code (a, b, poleOne, poleTwo)} or {@code (b, a, poleTwo,
     * poleOne)} produce the exact same interior point POSITIONS — see class doc for why
     * this is the mechanism cross-mesh watertightness depends on.
     */
    public static List<Point2D> displacedPolyline(Point2D a, Point2D b, long worldSeed, Point2D poleOne, Point2D poleTwo) {
        if (a.equals(b)) {
            throw new IllegalArgumentException("edge endpoints must be distinct, both were " + a);
        }

        boolean reversed = !isCanonicallyFirst(a, b);
        Point2D lower = reversed ? b : a;
        Point2D upper = reversed ? a : b;

        List<Point2D> canonical = canonicalPolyline(lower, upper, worldSeed, poleOne, poleTwo);
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

    private static List<Point2D> canonicalPolyline(Point2D lower, Point2D upper, long worldSeed, Point2D poleOne, Point2D poleTwo) {
        Point2D chord = vectorFrom(lower, upper);
        double length = Math.hypot(chord.x(), chord.z());
        Point2D perpendicular = new Point2D(-chord.z() / length, chord.x() / length);
        double intensity = edgeIntensity(lower, upper, worldSeed);

        List<Point2D> points = new ArrayList<>(INTERIOR_POINT_COUNT + 2);
        points.add(lower);
        for (int pointIndex = 1; pointIndex <= INTERIOR_POINT_COUNT; pointIndex++) {
            points.add(displacedInteriorPoint(lower, upper, poleOne, poleTwo, perpendicular, intensity, worldSeed, pointIndex));
        }
        points.add(upper);
        return chaikinSmooth(points, SMOOTHING_ITERATIONS);
    }

    /** One interior point's final displaced position - base lerp position along chord, offset perpendicular by a signed, doubly-clamped (per-pole safe range, then {@link #SAFETY_MARGIN}) random amount. */
    private static Point2D displacedInteriorPoint(
        Point2D lower, Point2D upper, Point2D poleOne, Point2D poleTwo, Point2D perpendicular, double intensity, long worldSeed, int pointIndex
    ) {
        double t = (double) pointIndex / (INTERIOR_POINT_COUNT + 1);
        Point2D base = lerp(lower, upper, t);
        Point2D prevStatic = pointIndex == 1 ? lower : lerp(lower, upper, (double) (pointIndex - 1) / (INTERIOR_POINT_COUNT + 1));
        Point2D nextStatic = pointIndex == INTERIOR_POINT_COUNT ? upper : lerp(lower, upper, (double) (pointIndex + 1) / (INTERIOR_POINT_COUNT + 1));

        OffsetRange range = safeOffsetRange(poleOne, base, prevStatic, nextStatic, perpendicular);
        if (poleTwo != null) {
            OffsetRange otherRange = safeOffsetRange(poleTwo, base, prevStatic, nextStatic, perpendicular);
            range = range.tightestOf(otherRange);
        }
        double safePositiveLimit = range.positiveLimit() * SAFETY_MARGIN;
        double safeNegativeLimit = range.negativeLimit() * SAFETY_MARGIN;

        double raw = signedUnitOffset(lower, upper, worldSeed, pointIndex);
        double offset = (raw >= 0 ? raw * safePositiveLimit : raw * safeNegativeLimit) * intensity;
        return new Point2D(base.x() + perpendicular.x() * offset, base.z() + perpendicular.z() * offset);
    }

    /**
     * Without this, every edge uses close to its full safe range, so every hex gets a
     * similarly-sized bulge on all 6 edges - unwanted uniformity (every patch reads as
     * the same "6-lobed flower" silhouette, just smoother). Scales the whole edge's offset
     * by one extra edge-level random factor - some edges stay calm, others use nearly all
     * available room, like a coastline with wild capes AND quiet stretches, not uniform
     * wobble. Same hash idiom, different salt (no correlation with per-point offsets),
     * same symmetric (lower, upper) inputs so both faces sharing this edge compute the
     * identical intensity with no coordination.
     */
    private static double edgeIntensity(Point2D lower, Point2D upper, long worldSeed) {
        long seed = worldSeed ^ INTENSITY_SALT;
        seed = seed * HASH_MULTIPLIER + Double.doubleToLongBits(lower.x());
        seed = seed * HASH_MULTIPLIER + Double.doubleToLongBits(lower.z());
        seed = seed * HASH_MULTIPLIER + Double.doubleToLongBits(upper.x());
        seed = seed * HASH_MULTIPLIER + Double.doubleToLongBits(upper.z());
        Random random = new Random(seed);
        random.nextDouble();
        return MIN_EDGE_INTENSITY + (1.0 - MIN_EDGE_INTENSITY) * random.nextDouble();
    }

    /**
     * Rounds the sharp zigzag between widely-swinging interior points into a gentle curve —
     * {@link #INTERIOR_POINT_COUNT} independent points connected by straight segments reads
     * spiky/starburst whenever two neighbors swing to opposite sides, since a zigzag of
     * straight lines has a corner at every point, not a curve.
     *
     * <p><b>Provably safe, no new verification needed</b> — only preserves safety already
     * established above. Standard open-curve Chaikin corner-cutting: two endpoints kept
     * EXACTLY (real hex corners other edges connect to), every other point replaced by two
     * points at {@link #FIRST_CUT_T}/{@link #SECOND_CUT_T} along its original segment. Every
     * point this produces, any iteration depth, lies exactly ON a straight segment between
     * two points already proven angularly safe (from both owning poles) - a straight
     * segment between two angularly-ordered-safe points is itself entirely safe, since no
     * edge here spans close to half the full circle around either pole (checkable fact for
     * hex geometry, not assumed), so the radial view along that segment can't double back.
     * Recursion doesn't weaken this: each iteration only interpolates between points from
     * the previous iteration, themselves safe by the same argument, on strictly smaller
     * sub-segments of the original safe ones.
     */
    private static List<Point2D> chaikinSmooth(List<Point2D> points, int iterations) {
        List<Point2D> current = points;
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<Point2D> next = new ArrayList<>();
            next.add(current.get(0));
            for (int segmentIndex = 0; segmentIndex < current.size() - 1; segmentIndex++) {
                Point2D segmentStart = current.get(segmentIndex);
                Point2D segmentEnd = current.get(segmentIndex + 1);
                next.add(lerp(segmentStart, segmentEnd, FIRST_CUT_T));
                next.add(lerp(segmentStart, segmentEnd, SECOND_CUT_T));
            }
            next.add(current.get(current.size() - 1));
            current = next;
        }
        return current;
    }

    private static Point2D lerp(Point2D from, Point2D to, double t) {
        return new Point2D(from.x() + (to.x() - from.x()) * t, from.z() + (to.z() - from.z()) * t);
    }

    /** {@code to - from}, as a {@link Point2D} used purely as a 2D vector (no separate vector type - {@link Point2D}'s shape already IS a vector, nothing else adds information). */
    private static Point2D vectorFrom(Point2D from, Point2D to) {
        return new Point2D(to.x() - from.x(), to.z() - from.z());
    }

    /** One point's safe perpendicular-offset range against ONE pole - {@code positiveLimit}/{@code negativeLimit} both non-negative magnitudes (how far {@code base} may move each direction along the perpendicular before crossing an angular bisector), never signed. */
    private record OffsetRange(double positiveLimit, double negativeLimit) {
        /** Tighter (smaller) bound each direction between this range and {@code other} - used when a shared edge's two owning faces each compute their own bound and caller must respect the stricter. */
        OffsetRange tightestOf(OffsetRange other) {
            return new OffsetRange(Math.min(positiveLimit, other.positiveLimit), Math.min(negativeLimit, other.negativeLimit));
        }
    }

    /**
     * How far {@code base} can move along {@code perpendicular} each direction before
     * crossing the angular bisector toward its static neighbor (as seen from {@code
     * pole}) — the bisector (not the neighbor's raw angle) gives BOTH points real margin,
     * approached from opposite sides of the same line — neither needs the other's actual
     * (still unresolved) perturbation, only their shared UNPERTURBED positions.
     */
    private static OffsetRange safeOffsetRange(Point2D pole, Point2D base, Point2D prevStatic, Point2D nextStatic, Point2D perpendicular) {
        Point2D baseVector = vectorFrom(pole, base);
        Point2D thisDirection = unitDirection(base, pole);
        Point2D prevDirection = unitDirection(prevStatic, pole);
        Point2D nextDirection = unitDirection(nextStatic, pole);
        Point2D prevBisector = normalizedBisector(prevDirection, thisDirection);
        Point2D nextBisector = normalizedBisector(thisDirection, nextDirection);

        double radiusCap = MAX_RADIUS_FRACTION * Math.hypot(baseVector.x(), baseVector.z());
        double positiveLimit = radiusCap;
        double negativeLimit = radiusCap;
        for (Point2D bisector : List.of(prevBisector, nextBisector)) {
            double crossingD = crossingOffset(baseVector, perpendicular, bisector);
            if (Double.isNaN(crossingD)) {
                continue;
            }
            if (crossingD > 0) {
                positiveLimit = Math.min(positiveLimit, crossingD);
            } else {
                negativeLimit = Math.min(negativeLimit, -crossingD);
            }
        }
        return new OffsetRange(positiveLimit, negativeLimit);
    }

    private static Point2D unitDirection(Point2D point, Point2D pole) {
        Point2D vector = vectorFrom(pole, point);
        double length = Math.hypot(vector.x(), vector.z());
        if (length < DEGENERATE_LENGTH_EPSILON_BLOCKS) {
            throw new IllegalStateException("EdgeDisplacement: pole " + pole + " coincides with boundary point " + point + " - degenerate face geometry");
        }
        return new Point2D(vector.x() / length, vector.z() / length);
    }

    /** Angular bisector direction between two unit vectors, via normalized vector sum (robust vs. raw-angle-averaging wraparound issues). Falls back to a perpendicular of {@code firstDirection} when the two point exactly opposite (sum zero, bisector undefined) - measure-zero case for real mesh geometry, not depended on being exact. */
    private static Point2D normalizedBisector(Point2D firstDirection, Point2D secondDirection) {
        double sumX = firstDirection.x() + secondDirection.x();
        double sumZ = firstDirection.z() + secondDirection.z();
        double length = Math.hypot(sumX, sumZ);
        if (length < DEGENERATE_LENGTH_EPSILON_BLOCKS) {
            return new Point2D(-firstDirection.z(), firstDirection.x());
        }
        return new Point2D(sumX / length, sumZ / length);
    }

    /**
     * Signed offset {@code d} where {@code (baseVector + d*perpendicular)} becomes
     * collinear with {@code boundaryDirection} FROM THE POLE (lands on the ray through pole
     * in that direction, not opposite ray) — solved directly from linear (in {@code d}) 2D
     * cross product {@code cross(boundaryDirection, baseVector + d*perpendicular) = 0}, not
     * searched. {@link Double#NaN} when {@code perpendicular} is parallel to {@code
     * boundaryDirection} (never reaches that ray) or the only collinear point lies on the
     * OPPOSITE ray (checked via dot product sign).
     */
    private static double crossingOffset(Point2D baseVector, Point2D perpendicular, Point2D boundaryDirection) {
        double crossBoundaryBase = boundaryDirection.x() * baseVector.z() - boundaryDirection.z() * baseVector.x();
        double crossBoundaryPerp = boundaryDirection.x() * perpendicular.z() - boundaryDirection.z() * perpendicular.x();
        if (Math.abs(crossBoundaryPerp) < PARALLEL_EPSILON) {
            return Double.NaN;
        }
        double crossingD = -crossBoundaryBase / crossBoundaryPerp;
        double crossX = baseVector.x() + crossingD * perpendicular.x();
        double crossZ = baseVector.z() + crossingD * perpendicular.z();
        if (crossX * boundaryDirection.x() + crossZ * boundaryDirection.z() <= 0) {
            return Double.NaN;
        }
        return crossingD;
    }

    /**
     * Deterministic value in {@code [-1, 1)} for one interior point - FRESH {@link Random}
     * per call, never a persisted stream (matches {@link SeedGrid}'s idiom). Discards
     * {@link Random}'s FIRST draw before using second - see DECISIONS.md 2026-09-04
     * "signedUnitOffset discards Random's first draw" for the hash-collapse bug this fixes.
     */
    private static double signedUnitOffset(Point2D lower, Point2D upper, long worldSeed, int pointIndex) {
        long seed = worldSeed ^ SALT;
        seed = seed * HASH_MULTIPLIER + Double.doubleToLongBits(lower.x());
        seed = seed * HASH_MULTIPLIER + Double.doubleToLongBits(lower.z());
        seed = seed * HASH_MULTIPLIER + Double.doubleToLongBits(upper.x());
        seed = seed * HASH_MULTIPLIER + Double.doubleToLongBits(upper.z());
        seed = seed * HASH_MULTIPLIER + pointIndex;
        Random random = new Random(seed);
        random.nextDouble(); // discarded - see method doc
        return random.nextDouble() * UNIFORM_TO_SIGNED_SCALE - UNIFORM_TO_SIGNED_OFFSET;
    }
}
