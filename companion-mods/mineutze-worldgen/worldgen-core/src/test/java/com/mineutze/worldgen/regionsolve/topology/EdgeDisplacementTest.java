package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeDisplacementTest {

    private static final Point2D A = new Point2D(0, 0);
    private static final Point2D B = new Point2D(1000, 0);
    private static final Point2D POLE_ABOVE = new Point2D(500, 600);
    private static final Point2D POLE_BELOW = new Point2D(500, -600);

    /**
     * The actual mechanism cross-mesh watertightness depends on: two independently-built
     * meshes (e.g. two neighboring regions) must compute the identical curve for a shared
     * edge with no live coordination - calling with endpoints AND poles reversed (matching
     * how the OTHER owning face would call this for the same edge) must produce the exact
     * same point POSITIONS, just walked backwards.
     */
    @Test
    void reversingEndpointsAndPolesReversesTheSamePolyline() {
        List<Point2D> forward = EdgeDisplacement.displacedPolyline(A, B, 42L, POLE_ABOVE, POLE_BELOW);
        List<Point2D> backward = EdgeDisplacement.displacedPolyline(B, A, 42L, POLE_BELOW, POLE_ABOVE);

        List<Point2D> reversedForward = new ArrayList<>(forward);
        Collections.reverse(reversedForward);
        assertEquals(reversedForward, backward);
    }

    @Test
    void sameInputsProduceTheSamePolyline() {
        List<Point2D> first = EdgeDisplacement.displacedPolyline(A, B, 7L, POLE_ABOVE, POLE_BELOW);
        List<Point2D> second = EdgeDisplacement.displacedPolyline(A, B, 7L, POLE_ABOVE, POLE_BELOW);
        assertEquals(first, second);
    }

    @Test
    void differentWorldSeedsProduceDifferentCurves() {
        List<Point2D> curveA = EdgeDisplacement.displacedPolyline(A, B, 1L, POLE_ABOVE, POLE_BELOW);
        List<Point2D> curveB = EdgeDisplacement.displacedPolyline(A, B, 2L, POLE_ABOVE, POLE_BELOW);
        assertTrue(!curveA.equals(curveB), "expected different world seeds to produce different curves");
    }

    @Test
    void startsAtAAndEndsAtB() {
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 5L, POLE_ABOVE, POLE_BELOW);
        assertEquals(A, polyline.get(0));
        assertEquals(B, polyline.get(polyline.size() - 1));
    }

    @Test
    void hasMultipleInteriorPointsNotOneSingleBump() {
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 5L, POLE_ABOVE, POLE_BELOW);
        assertTrue(polyline.size() > 3, "expected more than one interior point (start + one bump + end), got " + polyline.size() + " points total");
    }

    /**
     * The exact regression this test prevents: an EARLIER version of {@code
     * EdgeDisplacement.signedUnitOffset} folded {@code pointIndex} into the hash as its
     * last step with no further mixing, so the 3 "independent" interior points agreed to
     * 9 decimal places - not independent at all, just one offset value read 3 times. Real
     * interior points must genuinely scatter, not cluster near one value.
     */
    @Test
    void interiorPointsAreGenuinelyIndependentNotAllNearlyTheSameOffset() {
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 2026L, POLE_ABOVE, POLE_BELOW);
        List<Point2D> interior = polyline.subList(1, polyline.size() - 1);
        double first = interior.get(0).z();
        boolean foundARealDifference = false;
        for (Point2D point : interior) {
            if (Math.abs(point.z() - first) > 0.5) {
                foundARealDifference = true;
            }
        }
        assertTrue(foundARealDifference, "expected the interior points' own perpendicular offsets to genuinely differ, not cluster near " + first);
    }

    /** A hull-touching edge (only one real owning face) must still produce a valid polyline - the second pole being absent removes one constraint, not the whole mechanism. */
    @Test
    void nullSecondPoleStillProducesAValidPolyline() {
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 5L, POLE_ABOVE, null);
        assertEquals(A, polyline.get(0));
        assertEquals(B, polyline.get(polyline.size() - 1));
        assertTrue(polyline.size() > 3);
    }

    @Test
    void rejectsCoincidentEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> EdgeDisplacement.displacedPolyline(A, A, 1L, POLE_ABOVE, POLE_BELOW));
    }

    /**
     * The actual by-construction safety guarantee, checked directly not trusted: every
     * interior point's angle around EACH of its two owning poles must stay strictly
     * between its two immediate static neighbors' angles from that SAME pole - exactly
     * what "star-shaped, hence simple and connected" means, verified independently of the
     * production implementation (fresh angle computation here, not a call into {@code
     * EdgeDisplacement}'s private geometry).
     */
    @Test
    void everyInteriorPointStaysAngularlyBetweenItsOwnStaticNeighborsFromBothPoles() {
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 99L, POLE_ABOVE, POLE_BELOW);
        for (Point2D pole : List.of(POLE_ABOVE, POLE_BELOW)) {
            for (int i = 1; i < polyline.size() - 1; i++) {
                double prevAngle = angleFrom(pole, polyline.get(i - 1));
                double thisAngle = angleFrom(pole, polyline.get(i));
                double nextAngle = angleFrom(pole, polyline.get(i + 1));
                assertTrue(
                    isStrictlyBetweenCyclically(prevAngle, thisAngle, nextAngle),
                    "point " + polyline.get(i) + " (index " + i + ") is not angularly between its own static neighbors from pole " + pole
                );
            }
        }
    }

    /**
     * A narrow safe corridor (a pole placed close enough to the chord that the angular
     * room between static neighbors is small) must genuinely constrain the offset, not
     * just theoretically bound it - the closed-form clamp must actually activate on a
     * real input, not be a bound nothing ever reaches.
     */
    @Test
    void aNarrowSafeCorridorActuallyConstrainsTheOffsetBelowAPermissiveOne() {
        Point2D tightPole = new Point2D(500, 5); // very close to the chord itself
        List<Point2D> constrained = EdgeDisplacement.displacedPolyline(A, B, 42L, tightPole, tightPole);
        List<Point2D> permissive = EdgeDisplacement.displacedPolyline(A, B, 42L, POLE_ABOVE, POLE_BELOW);
        double maxConstrainedOffset = maxAbsOffsetFromChord(constrained);
        double maxPermissiveOffset = maxAbsOffsetFromChord(permissive);
        assertTrue(
            maxConstrainedOffset < maxPermissiveOffset,
            "expected a tight pole placement to produce a smaller realized offset than a permissive one, got constrained="
                + maxConstrainedOffset + " permissive=" + maxPermissiveOffset
        );
    }

    private static double maxAbsOffsetFromChord(List<Point2D> polyline) {
        double max = 0;
        for (Point2D point : polyline) {
            max = Math.max(max, Math.abs(point.z()));
        }
        return max;
    }

    private static double angleFrom(Point2D pole, Point2D point) {
        return Math.atan2(point.z() - pole.z(), point.x() - pole.x());
    }

    /** True if walking from {@code prevAngle} toward {@code nextAngle} the SAME rotational direction that walk itself takes passes through {@code thisAngle} strictly before reaching {@code nextAngle} - handles wraparound at +/-pi without assuming a winding direction ahead of time. */
    private static boolean isStrictlyBetweenCyclically(double prevAngle, double thisAngle, double nextAngle) {
        double totalSpan = normalizeAngle(nextAngle - prevAngle);
        double toThis = normalizeAngle(thisAngle - prevAngle);
        if (totalSpan > 0) {
            return toThis > 1e-9 && toThis < totalSpan - 1e-9;
        }
        return toThis < -1e-9 && toThis > totalSpan + 1e-9;
    }

    /** Into {@code (-pi, pi]}. */
    private static double normalizeAngle(double angle) {
        double twoPi = 2 * Math.PI;
        double result = angle % twoPi;
        if (result > Math.PI) {
            result -= twoPi;
        }
        if (result < -Math.PI) {
            result += twoPi;
        }
        return result;
    }
}
