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
 * <p><b>Wiggle only, no separate bias knob</b> — macro-scale sizing lives
 * entirely in WHERE the two endpoints are ({@link WeightedVertexPlacement}),
 * not in a mid-edge additive term.
 *
 * <p><b>Amplitude is derived from geometry, not a tuned fraction — a
 * by-construction safety guarantee, not a checked one (2026-09-04
 * redesign).</b> The original version pushed each interior point
 * perpendicular to the chord by a flat {@code amplitude} parameter (a fixed
 * fraction of edge length), with nothing verifying the result stayed
 * simple — a large enough amplitude could fold a face's own boundary onto
 * itself, producing exactly the disconnected-sliver/isolated-patch symptom
 * this redesign exists to prevent by construction, not by checking
 * afterward (checking the full wavy boundary for self-intersection would
 * need an O(n²) segment scan plus a search for how far to back off — real
 * cost and real complexity this design avoids entirely).
 *
 * <p>The core idea: a polygon is star-shaped with respect to a point when
 * every boundary point's ANGLE around that point is strictly increasing
 * while walking the boundary once — star-shaped implies both simple (no
 * self-crossing) and unified (one connected blob, no holes or pinched-off
 * islands), the same guarantee covering both properties at once. This class
 * NEVER perturbs a point's angle from either of its two owning faces' own
 * centers — only its RADIUS (via the existing perpendicular-to-chord
 * offset, decomposed into an angle component that stays fixed and a radius
 * component that moves). Each interior point's safe offset range is solved
 * in closed form (one linear equation per boundary — see {@link
 * #crossingOffset}), not searched for: the point at which moving further
 * perpendicular would cross the angular bisector toward its own static
 * neighbor (previous or next point on the SAME edge, using their
 * UNPERTURBED positions, known in full before any point moves — so no
 * ordering/coordination between points is needed at all). A shared edge's
 * two owning faces each compute this bound from their own center
 * independently and take the smaller of the two — both faces derive it
 * from the same shared inputs (same edge, same hash), so they agree with no
 * coordination, the same trick this class's own endpoint-hashing already
 * relies on for cross-mesh watertightness.
 *
 * <p><b>Open assumption, not yet independently verified:</b> this
 * construction requires each owning face's own STRAIGHT hexagon to already
 * be star-shaped with respect to its own center — true by construction for
 * a raw, unclamped power-diagram cell (those are provably convex), but
 * {@link WeightedVertexPlacement}'s adaptive clamp has only ever proven its
 * output SIMPLE, not star-shaped, after scaling. The two are not
 * automatically the same property.
 */
public final class EdgeDisplacement {

    private static final long SALT = Salt.of("EdgeDisplacement.wiggle");
    private static final int INTERIOR_POINT_COUNT = 3;

    /**
     * Chaikin corner-cutting rounds applied to the finished polyline (see {@link
     * #chaikinSmooth}) — 2 is enough to turn a visibly sharp zigzag into a gentle curve
     * without exploding the point count per edge (each round roughly doubles it: 5 points
     * become 10, then 19-ish after two rounds, still cheap for a 6-edge hex face).
     */
    private static final int SMOOTHING_ITERATIONS = 2;

    /**
     * Shrinks every closed-form safe range before using it, so a point never lands
     * EXACTLY on its own angular boundary (where floating-point error could tip it
     * across) and two neighbors approaching the same bisector from opposite sides never
     * both reach it at once. Deliberately close to 1 — the closed-form range is already
     * the real geometric limit, not a heuristic guess that needed headroom baked in.
     */
    private static final double SAFETY_MARGIN = 0.9;

    /**
     * Universal supplementary cap: a point may never move closer than this fraction of
     * its OWN current distance to a pole - regardless of what the bisector-crossing
     * formula says. Found as a real bug, not a defensive guess added out of caution: when
     * the perpendicular direction happens to be exactly (anti)parallel to the direction
     * from base to pole, the point's angle from that pole barely changes at all as it
     * moves - right up until it passes through the pole itself, where angle is undefined
     * and flips discontinuously. The bisector formula's own "same side of the ray" check
     * (see {@link #crossingOffset}) rejects that crossing as invalid (its dot product
     * lands at exactly zero), leaving the bisector-only computation with NO limit at all
     * in that direction - measured directly: a synthetic test with the pole placed
     * exactly above an edge's own midpoint produced an literal {@link
     * Double#POSITIVE_INFINITY} safe range for that edge's middle interior point. This
     * isn't a rare alignment either - a REGULAR hexagon's own center sits exactly on the
     * perpendicular bisector of every one of its edges, by symmetry, so this hits the
     * middle interior point of essentially every edge in a near-regular mesh, not an
     * unlucky corner case. This cap catches it unconditionally, cheap to always apply,
     * and in the normal (non-degenerate) case is generous enough to rarely be the
     * tighter of the two bounds anyway (the bisector cap already came out to ~29% of
     * radius in the hand-verified non-degenerate case this bug was found alongside).
     */
    private static final double MAX_RADIUS_FRACTION = 0.5;

    private EdgeDisplacement() {
    }

    /**
     * The chord from {@code a} to {@code b}, displaced into a
     * {@code (2 + INTERIOR_POINT_COUNT)}-point polyline starting at {@code a} and ending
     * at {@code b} (in that order, whichever physical endpoint either one is). Every
     * interior point is offset perpendicular to the chord by an independently-hashed
     * random amount, clamped in closed form (see this class's own doc) to whatever its
     * two owning faces' own geometry can safely tolerate — {@code poleOne} and {@code
     * poleTwo} are those two faces' own centers (their straight polygon's centroid, not
     * necessarily {@link WeightedVertexPlacement}'s original lattice point); {@code
     * poleTwo} is {@code null} for a hull-touching edge with only one real owning face.
     *
     * <p>Order-independent: calling with {@code (a, b, poleOne, poleTwo)} or
     * {@code (b, a, poleTwo, poleOne)} — either endpoint order, either pole order —
     * produces the exact same set of interior point POSITIONS (walked in the matching
     * direction for whichever endpoint order was requested) — see this class's own doc
     * for why that is the actual mechanism cross-mesh watertightness depends on.
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
        double dx = upper.x() - lower.x();
        double dz = upper.z() - lower.z();
        double length = Math.hypot(dx, dz);
        double perpX = -dz / length;
        double perpZ = dx / length;

        List<Point2D> points = new ArrayList<>(INTERIOR_POINT_COUNT + 2);
        points.add(lower);
        for (int i = 1; i <= INTERIOR_POINT_COUNT; i++) {
            double t = (double) i / (INTERIOR_POINT_COUNT + 1);
            Point2D base = lerp(lower, upper, t);
            Point2D prevStatic = i == 1 ? lower : lerp(lower, upper, (double) (i - 1) / (INTERIOR_POINT_COUNT + 1));
            Point2D nextStatic = i == INTERIOR_POINT_COUNT ? upper : lerp(lower, upper, (double) (i + 1) / (INTERIOR_POINT_COUNT + 1));

            double[] range = safeOffsetRange(poleOne, base, prevStatic, nextStatic, perpX, perpZ);
            if (poleTwo != null) {
                double[] otherRange = safeOffsetRange(poleTwo, base, prevStatic, nextStatic, perpX, perpZ);
                range[0] = Math.min(range[0], otherRange[0]);
                range[1] = Math.min(range[1], otherRange[1]);
            }
            double dPositiveLimit = range[0] * SAFETY_MARGIN;
            double dNegativeLimit = range[1] * SAFETY_MARGIN;

            double raw = signedUnitOffset(lower, upper, worldSeed, i);
            double offset = raw >= 0 ? raw * dPositiveLimit : raw * dNegativeLimit;
            points.add(new Point2D(base.x() + perpX * offset, base.z() + perpZ * offset));
        }
        points.add(upper);
        return chaikinSmooth(points, SMOOTHING_ITERATIONS);
    }

    /**
     * Rounds off the sharp zigzag between widely-swinging interior points into a gentle
     * curve — {@link #INTERIOR_POINT_COUNT} independent points connected by straight
     * segments reads as spiky/starburst whenever two neighbors swing to opposite sides,
     * since a zigzag of straight lines has a real corner at every point, not a curve.
     *
     * <p><b>Provably safe without any new verification</b> — this only ever needs to
     * preserve the safety already established above, not re-derive it. Standard open-curve
     * Chaikin corner-cutting: the two endpoints are kept EXACTLY (they're the real hex
     * corners other edges connect to), and every other point is replaced by two points at
     * 25% and 75% along its own original segment. Every point this ever produces, at any
     * iteration depth, lies exactly ON a straight segment between two points that were
     * already proven angularly safe (from both owning poles) - and a straight segment
     * between two angularly-ordered-safe points is itself entirely safe, since none of our
     * edges ever span close to half the full circle around either pole (a real, checkable
     * fact for hex geometry, not assumed), so the radial view along that segment can't
     * double back on itself. Recursion doesn't weaken this: each later iteration only ever
     * interpolates between points from the previous iteration, which are themselves safe
     * by the same argument, on strictly smaller sub-segments of the original safe ones.
     */
    private static List<Point2D> chaikinSmooth(List<Point2D> points, int iterations) {
        List<Point2D> current = points;
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<Point2D> next = new ArrayList<>();
            next.add(current.get(0));
            for (int i = 0; i < current.size() - 1; i++) {
                Point2D p = current.get(i);
                Point2D q = current.get(i + 1);
                next.add(lerp(p, q, 0.25));
                next.add(lerp(p, q, 0.75));
            }
            next.add(current.get(current.size() - 1));
            current = next;
        }
        return current;
    }

    private static Point2D lerp(Point2D from, Point2D to, double t) {
        return new Point2D(from.x() + (to.x() - from.x()) * t, from.z() + (to.z() - from.z()) * t);
    }

    /**
     * How far {@code base} can move along {@code (perpX, perpZ)} in each direction
     * before it crosses the angular bisector toward its own static neighbor (as seen
     * from {@code pole}) — {@code {dPositiveLimit, dNegativeLimitMagnitude}}, both
     * non-negative, {@link Double#POSITIVE_INFINITY} where a boundary doesn't bind in
     * that direction at all. The bisector (not the neighbor's own raw angle) is what
     * gives BOTH this point and that neighbor real margin, approached from opposite
     * sides of the same line — neither needs to know the other's actual (still
     * unresolved) perturbation, only their shared UNPERTURBED positions.
     */
    private static double[] safeOffsetRange(Point2D pole, Point2D base, Point2D prevStatic, Point2D nextStatic, double perpX, double perpZ) {
        double baseVx = base.x() - pole.x();
        double baseVz = base.z() - pole.z();
        double[] thisDir = unitDirection(base, pole);
        double[] prevDir = unitDirection(prevStatic, pole);
        double[] nextDir = unitDirection(nextStatic, pole);
        double[] prevBisector = normalizedBisector(prevDir, thisDir);
        double[] nextBisector = normalizedBisector(thisDir, nextDir);

        double radiusCap = MAX_RADIUS_FRACTION * Math.hypot(baseVx, baseVz);
        double dPositive = radiusCap;
        double dNegative = radiusCap;
        for (double[] bisector : new double[][] {prevBisector, nextBisector}) {
            double d = crossingOffset(baseVx, baseVz, perpX, perpZ, bisector[0], bisector[1]);
            if (Double.isNaN(d)) {
                continue;
            }
            if (d > 0) {
                dPositive = Math.min(dPositive, d);
            } else {
                dNegative = Math.min(dNegative, -d);
            }
        }
        return new double[] {dPositive, dNegative};
    }

    private static double[] unitDirection(Point2D point, Point2D pole) {
        double vx = point.x() - pole.x();
        double vz = point.z() - pole.z();
        double length = Math.hypot(vx, vz);
        if (length < 1e-9) {
            throw new IllegalStateException("EdgeDisplacement: pole " + pole + " coincides with boundary point " + point + " - degenerate face geometry");
        }
        return new double[] {vx / length, vz / length};
    }

    /** The angular bisector direction between two unit vectors, via normalized vector sum (robust across the wraparound issues raw angle averaging would have). Falls back to a perpendicular of {@code u} when {@code u} and {@code v} point in exactly opposite directions (sum is zero, bisector is genuinely undefined) - a measure-zero case for real mesh geometry, not one this construction depends on being exact. */
    private static double[] normalizedBisector(double[] u, double[] v) {
        double sumX = u[0] + v[0];
        double sumZ = u[1] + v[1];
        double length = Math.hypot(sumX, sumZ);
        if (length < 1e-9) {
            return new double[] {-u[1], u[0]};
        }
        return new double[] {sumX / length, sumZ / length};
    }

    /**
     * The signed {@code d} at which {@code (baseV + d*perp)} becomes collinear with
     * {@code boundaryDir} FROM THE POLE (i.e. lands on the ray through the pole in that
     * direction, not the opposite ray) — solved directly from the linear (in {@code d})
     * 2D cross product {@code cross(boundaryDir, baseV + d*perp) = 0}, not searched for.
     * {@link Double#NaN} when {@code perp} is parallel to {@code boundaryDir} (moving
     * along {@code perp} never reaches that particular ray) or when the only collinear
     * point lies on the OPPOSITE ray (checked via the dot product sign).
     */
    private static double crossingOffset(double baseVx, double baseVz, double perpX, double perpZ, double boundaryDirX, double boundaryDirZ) {
        double k = boundaryDirX * baseVz - boundaryDirZ * baseVx;
        double m = boundaryDirX * perpZ - boundaryDirZ * perpX;
        if (Math.abs(m) < 1e-12) {
            return Double.NaN;
        }
        double d = -k / m;
        double crossVx = baseVx + d * perpX;
        double crossVz = baseVz + d * perpZ;
        if (crossVx * boundaryDirX + crossVz * boundaryDirZ <= 0) {
            return Double.NaN;
        }
        return d;
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
