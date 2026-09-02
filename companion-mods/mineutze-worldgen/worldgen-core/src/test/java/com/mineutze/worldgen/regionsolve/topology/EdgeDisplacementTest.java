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

    /**
     * The actual mechanism cross-mesh watertightness depends on: two independently-built
     * meshes (e.g. two neighboring regions) must compute the identical curve for a shared
     * physical edge with no live coordination - calling with endpoints reversed must
     * produce the exact same set of point POSITIONS, just walked backwards.
     */
    @Test
    void reversingEndpointsReversesTheSamePolyline() {
        List<Point2D> forward = EdgeDisplacement.displacedPolyline(A, B, 42L, 50.0);
        List<Point2D> backward = EdgeDisplacement.displacedPolyline(B, A, 42L, 50.0);

        List<Point2D> reversedForward = new ArrayList<>(forward);
        Collections.reverse(reversedForward);
        assertEquals(reversedForward, backward);
    }

    @Test
    void sameInputsProduceTheSamePolyline() {
        List<Point2D> first = EdgeDisplacement.displacedPolyline(A, B, 7L, 30.0);
        List<Point2D> second = EdgeDisplacement.displacedPolyline(A, B, 7L, 30.0);
        assertEquals(first, second);
    }

    @Test
    void differentWorldSeedsProduceDifferentCurves() {
        List<Point2D> curveA = EdgeDisplacement.displacedPolyline(A, B, 1L, 40.0);
        List<Point2D> curveB = EdgeDisplacement.displacedPolyline(A, B, 2L, 40.0);
        assertTrue(!curveA.equals(curveB), "expected different world seeds to produce different curves");
    }

    @Test
    void startsAtAAndEndsAtB() {
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 5L, 25.0);
        assertEquals(A, polyline.get(0));
        assertEquals(B, polyline.get(polyline.size() - 1));
    }

    @Test
    void hasMultipleInteriorPointsNotOneSingleBump() {
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 5L, 25.0);
        assertTrue(polyline.size() > 3, "expected more than one interior point (start + one bump + end), got " + polyline.size() + " points total");
    }

    @Test
    void everyInteriorPointStaysWithinWiggleAmplitudeOfTheChord() {
        double amplitude = 15.0;
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 9L, amplitude);
        // chord is along the x-axis here, so perpendicular distance is just |z|
        for (Point2D point : polyline) {
            assertTrue(Math.abs(point.z()) <= amplitude + 1e-9, "point " + point + " strayed further than amplitude " + amplitude + " from the straight chord");
        }
    }

    @Test
    void zeroWiggleCollapsesToTheStraightChord() {
        List<Point2D> polyline = EdgeDisplacement.displacedPolyline(A, B, 3L, 0.0);
        for (Point2D point : polyline) {
            assertEquals(0.0, point.z(), 1e-9);
        }
    }

    @Test
    void rejectsNegativeWiggleAmplitude() {
        assertThrows(IllegalArgumentException.class, () -> EdgeDisplacement.displacedPolyline(A, B, 1L, -1.0));
    }

    @Test
    void rejectsCoincidentEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> EdgeDisplacement.displacedPolyline(A, A, 1L, 10.0));
    }
}
