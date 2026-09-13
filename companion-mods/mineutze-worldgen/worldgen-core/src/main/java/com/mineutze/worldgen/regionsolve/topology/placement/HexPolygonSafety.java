package com.mineutze.worldgen.regionsolve.topology.placement;

import com.mineutze.worldgen.regionsolve.topology.HexGeometry;

import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometric validity and safe-scaling for a hex face's 6 corners - given an always-valid
 * {@code original} shape and a {@code candidate} shape the size math wants, how far can
 * corners move toward it while staying a simple polygon with real edges and area? Zero
 * power-diagram/weight concept here, only point lists and bounds - see {@link
 * WeightedVertexPlacement} class doc for why the two concerns are split.
 */
final class HexPolygonSafety {

    /**
     * Bisection precision for {@link #maxSafeScale} — each iteration halves the search
     * interval, so 30 gives roughly {@code 2^-30} (~1e-9) relative precision on the
     * returned scale, far finer than block-scale geometry needs; cheap either way since
     * each iteration is one 6-corner polygon-simplicity check.
     */
    private static final int BISECTION_ITERATIONS = 30;

    /**
     * Floor on a face's realized area, as a fraction of ITS OWN target area - a THIRD
     * geometric validity condition alongside simplicity and minimum edge length, for a
     * different failure mode: a face with comfortably long edges and a perfectly simple
     * polygon can still land badly undersized. Relative to the face's own target, not
     * absolute (a 300-block-target biome realizing 250 is fine, a 1000-block one realizing
     * 250 is not). 0 disables the check for a face with no real target (fixed/hull-touching).
     *
     * <p><b>Capped at the baseline hexagon's area, not applied raw</b> - for an OVERSIZED
     * target, `0.7 x targetArea` can exceed the baseline hexagon's area, breaking the
     * binary search's "always valid at t=0" foundation and inverting the fix direction.
     * Capping the floor at baseline area restores that invariant: an oversized target's
     * floor becomes trivially satisfied, an undersized target's floor stays real. See
     * DECISIONS.md 2026-09-04 "MIN_REALIZED_AREA_FRACTION_OF_TARGET."
     */
    private static final double MIN_REALIZED_AREA_FRACTION_OF_TARGET = 0.7;

    /**
     * Ceiling on a face's realized area, as a fraction of ITS OWN target area - symmetric
     * counterpart to {@link #MIN_REALIZED_AREA_FRACTION_OF_TARGET}. Mirrors the floor's
     * inversion-prevention logic, flipped: capped with {@code Math.max(target x 1.3,
     * baselineArea)} so an UNDERSIZED target's ceiling never falls BELOW baseline area,
     * preserving the behavior that small-tier biomes commonly land near baseline size from
     * real surrounding competition. See DECISIONS.md 2026-09-11
     * "MAX_REALIZED_AREA_FRACTION_OF_TARGET."
     */
    private static final double MAX_REALIZED_AREA_FRACTION_OF_TARGET = 1.3;

    /**
     * 4th condition: cap vertex move at this fraction of baseline circumradius from
     * undisplaced position. Other 3 checks only see one face's own 6 corners - miss a face
     * grown far enough to reach a DISTANT non-adjacent face. 0.8 = max value empirically
     * clean of illegal adjacencies (1.0 broke correctness). See DECISIONS.md 2026-09-04,
     * 2026-09-06 (why 4th condition exists, why 0.9 reverted). Don't raise without re-testing.
     */
    private static final double MAX_VERTEX_DISPLACEMENT_FRACTION = 0.8;

    private HexPolygonSafety() {
    }

    /** The 3 area/displacement bounds {@link #maxSafeScale} needs, derived once from a face's target area, legal range, and the mesh's baseline circumradius - shared by every caller validating a face against those 3 quantities, instead of each re-deriving the same floor/ceiling-capped-at-baseline logic independently. */
    record SafetyBounds(double minAreaBlocks2, double maxAreaBlocks2, double maxDisplacementBlocks) {
    }

    /**
     * Same "cap floor/ceiling at baseline area" invariant {@link
     * #MIN_REALIZED_AREA_FRACTION_OF_TARGET} and {@link #MAX_REALIZED_AREA_FRACTION_OF_TARGET}
     * explain, ALSO intersected with the face's absolute legal-range bound (same "always
     * valid at t=0" invariant, capped against baseline the same way) - accepted
     * consequence for a biome whose ENTIRE legal range sits on one side of the fixed
     * lattice baseline: this can only pull a violation as far as baseline, not all the way
     * to the legal boundary, same tradeoff the target-relative ceiling carries for
     * small-tier biomes - not a new gap.
     *
     * @param targetArea {@code NaN} for a face with no real target (fixed/hull-touching) -
     *                   disables BOTH the target-relative floor AND ceiling (0 and
     *                   effectively-unconstrained), never just one; a single shared default
     *                   here previously collapsed to 0 for both sides, wrongly capping an
     *                   untargeted face's CEILING at baseline area too - a real bug caught
     *                   by the test suite right after this consolidation.
     */
    static SafetyBounds safetyBoundsFor(double targetArea, WeightedVertexPlacement.AreaRange legalAreaRange, double baselineCircumradiusBlocks) {
        double baselineAreaBlocks2 = HexGeometry.areaForCircumradius(baselineCircumradiusBlocks);
        double minAreaBlocks2 = Double.isNaN(targetArea) ? 0.0 : Math.min(targetArea * MIN_REALIZED_AREA_FRACTION_OF_TARGET, baselineAreaBlocks2);
        double maxAreaBlocks2 = Double.isNaN(targetArea) ? Double.MAX_VALUE : Math.max(targetArea * MAX_REALIZED_AREA_FRACTION_OF_TARGET, baselineAreaBlocks2);
        minAreaBlocks2 = Math.max(minAreaBlocks2, Math.min(legalAreaRange.minAreaBlocks2(), baselineAreaBlocks2));
        maxAreaBlocks2 = Math.min(maxAreaBlocks2, Math.max(legalAreaRange.maxAreaBlocks2(), baselineAreaBlocks2));
        double maxDisplacementBlocks = MAX_VERTEX_DISPLACEMENT_FRACTION * baselineCircumradiusBlocks;
        return new SafetyBounds(minAreaBlocks2, maxAreaBlocks2, maxDisplacementBlocks);
    }

    /**
     * Largest {@code t} in {@code [0, 1]} such that moving every {@code original} corner
     * {@code t} of the way toward its matching {@code candidate} corner keeps the polygon
     * {@link #isGeometricallyValid valid} (simple AND no edge shorter than {@code
     * minEdgeLengthBlocks}). Binary search, not closed form — no formula for "how far is
     * too far" once more than one corner has moved. Assumes validity monotonic in {@code
     * t} (true at {@code t=0}, since undisplaced regular hexagon is always simple with
     * every edge equal to baseline circumradius exactly; breaks past some threshold, never
     * flips back) — real assumption about this specific deformation (corners moving
     * continuously outward from a valid start), not a general truth about arbitrary
     * polygons. {@code minEdgeLengthBlocks} must stay below baseline circumradius for this
     * base case to hold — caller's responsibility, same as {@code
     * MAX_VERTEX_DISPLACEMENT_FRACTION} was.
     */
    static double maxSafeScale(
        List<Point2D> original, List<Point2D> candidate, double minEdgeLengthBlocks, double minAreaBlocks2, double maxAreaBlocks2, double maxDisplacementBlocks
    ) {
        if (isGeometricallyValid(original, lerpAll(original, candidate, 1.0), minEdgeLengthBlocks, minAreaBlocks2, maxAreaBlocks2, maxDisplacementBlocks)) {
            return 1.0;
        }
        double lowSafe = 0.0;
        double highUnsafe = 1.0;
        for (int iteration = 0; iteration < BISECTION_ITERATIONS; iteration++) {
            double mid = (lowSafe + highUnsafe) / 2.0;
            if (isGeometricallyValid(original, lerpAll(original, candidate, mid), minEdgeLengthBlocks, minAreaBlocks2, maxAreaBlocks2, maxDisplacementBlocks)) {
                lowSafe = mid;
            } else {
                highUnsafe = mid;
            }
        }
        return lowSafe;
    }

    private static boolean isGeometricallyValid(
        List<Point2D> original, List<Point2D> corners, double minEdgeLengthBlocks, double minAreaBlocks2, double maxAreaBlocks2, double maxDisplacementBlocks
    ) {
        return isSimplePolygon(corners) && hasMinimumEdgeLength(corners, minEdgeLengthBlocks) && hasMinimumArea(corners, minAreaBlocks2)
            && hasMaximumArea(corners, maxAreaBlocks2) && hasBoundedDisplacement(original, corners, maxDisplacementBlocks);
    }

    /** Each corner checked against its OWN original position - global cap independent of the other 5 corners' behavior, see {@link #MAX_VERTEX_DISPLACEMENT_FRACTION} for why the other three conditions can't provide this alone. */
    private static boolean hasBoundedDisplacement(List<Point2D> original, List<Point2D> corners, double maxDisplacementBlocks) {
        for (int cornerIndex = 0; cornerIndex < original.size(); cornerIndex++) {
            if (Math.hypot(corners.get(cornerIndex).x() - original.get(cornerIndex).x(), corners.get(cornerIndex).z() - original.get(cornerIndex).z()) > maxDisplacementBlocks) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasMinimumEdgeLength(List<Point2D> corners, double minEdgeLengthBlocks) {
        int cornerCount = corners.size();
        for (int edgeIndex = 0; edgeIndex < cornerCount; edgeIndex++) {
            Point2D current = corners.get(edgeIndex);
            Point2D next = corners.get((edgeIndex + 1) % cornerCount);
            if (Math.hypot(current.x() - next.x(), current.z() - next.z()) < minEdgeLengthBlocks) {
                return false;
            }
        }
        return true;
    }

    /** Shoelace area of this candidate hexagon against {@code minAreaBlocks2} - see {@link #MIN_REALIZED_AREA_FRACTION_OF_TARGET} for why this is a per-face RELATIVE floor, not absolute like the other two conditions. */
    private static boolean hasMinimumArea(List<Point2D> corners, double minAreaBlocks2) {
        return shoelaceArea(corners) >= minAreaBlocks2;
    }

    /** Symmetric ceiling - see {@link #MAX_REALIZED_AREA_FRACTION_OF_TARGET}. */
    private static boolean hasMaximumArea(List<Point2D> corners, double maxAreaBlocks2) {
        return shoelaceArea(corners) <= maxAreaBlocks2;
    }

    /** The one shoelace-area formula every corner-list-based area computation in this package shares - {@code MeshFaceGeometry.realizedAreasOf} and {@code SequentialVertexPlacement.shoelaceAreaOfFace} both build a corner list then delegate here, not each re-implementing the sum. */
    static double shoelaceArea(List<Point2D> corners) {
        int cornerCount = corners.size();
        double sum = 0;
        for (int cornerIndex = 0; cornerIndex < cornerCount; cornerIndex++) {
            Point2D current = corners.get(cornerIndex);
            Point2D next = corners.get((cornerIndex + 1) % cornerCount);
            sum += current.x() * next.z() - next.x() * current.z();
        }
        return Math.abs(sum) / 2.0;
    }

    static List<Point2D> lerpAll(List<Point2D> original, List<Point2D> candidate, double t) {
        List<Point2D> result = new ArrayList<>(original.size());
        for (int pointIndex = 0; pointIndex < original.size(); pointIndex++) {
            result.add(lerp(original.get(pointIndex), candidate.get(pointIndex), t));
        }
        return result;
    }

    static Point2D lerp(Point2D from, Point2D to, double t) {
        return new Point2D(from.x() + (to.x() - from.x()) * t, from.z() + (to.z() - from.z()) * t);
    }

    /**
     * True if no two of this polygon's NON-ADJACENT edges cross — adjacent edges
     * (consecutive corners) always legitimately share an endpoint, never checked against
     * each other. Plain segment-intersection over every non-adjacent edge pair; only 6
     * corners so a handful of checks, no performance concern.
     */
    private static boolean isSimplePolygon(List<Point2D> corners) {
        int cornerCount = corners.size();
        for (int edgeIndex = 0; edgeIndex < cornerCount; edgeIndex++) {
            Point2D edgeAStart = corners.get(edgeIndex);
            Point2D edgeAEnd = corners.get((edgeIndex + 1) % cornerCount);
            for (int otherEdgeIndex = edgeIndex + 2; otherEdgeIndex < cornerCount; otherEdgeIndex++) {
                if (edgeIndex == 0 && otherEdgeIndex == cornerCount - 1) {
                    continue; // edge (n-1, 0) is adjacent to edge (0, 1), wrapping around
                }
                Point2D edgeBStart = corners.get(otherEdgeIndex);
                Point2D edgeBEnd = corners.get((otherEdgeIndex + 1) % cornerCount);
                if (segmentsProperlyIntersect(edgeAStart, edgeAEnd, edgeBStart, edgeBEnd)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Standard orientation-based proper-crossing test: true only when each segment strictly straddles the other's line (shared or touching endpoints are NOT a crossing). */
    private static boolean segmentsProperlyIntersect(Point2D segmentAStart, Point2D segmentAEnd, Point2D segmentBStart, Point2D segmentBEnd) {
        double segmentAStartRelativeToB = orientation(segmentBStart, segmentBEnd, segmentAStart);
        double segmentAEndRelativeToB = orientation(segmentBStart, segmentBEnd, segmentAEnd);
        double segmentBStartRelativeToA = orientation(segmentAStart, segmentAEnd, segmentBStart);
        double segmentBEndRelativeToA = orientation(segmentAStart, segmentAEnd, segmentBEnd);
        return ((segmentAStartRelativeToB > 0 && segmentAEndRelativeToB < 0) || (segmentAStartRelativeToB < 0 && segmentAEndRelativeToB > 0))
            && ((segmentBStartRelativeToA > 0 && segmentBEndRelativeToA < 0) || (segmentBStartRelativeToA < 0 && segmentBEndRelativeToA > 0));
    }

    private static double orientation(Point2D origin, Point2D directionPoint, Point2D testPoint) {
        return (directionPoint.x() - origin.x()) * (testPoint.z() - origin.z()) - (directionPoint.z() - origin.z()) * (testPoint.x() - origin.x());
    }
}
