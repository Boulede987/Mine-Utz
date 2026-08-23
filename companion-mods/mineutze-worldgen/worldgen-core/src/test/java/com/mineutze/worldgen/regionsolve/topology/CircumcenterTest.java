package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CircumcenterTest {

    private static final double EPSILON = 1e-6;

    @Test
    void isEquidistantFromAllThreePointsOnARightTriangle() {
        Point2D a = new Point2D(0, 0);
        Point2D b = new Point2D(4, 0);
        Point2D c = new Point2D(0, 3);

        Point2D center = Circumcenter.of(a, b, c);

        assertEquals(distance(center, a), distance(center, b), EPSILON);
        assertEquals(distance(center, b), distance(center, c), EPSILON);
    }

    @Test
    void matchesTheKnownCenterOfARightTriangle() {
        // For a right triangle, the circumcenter is exactly the midpoint of the hypotenuse.
        Point2D a = new Point2D(0, 0);
        Point2D b = new Point2D(4, 0);
        Point2D c = new Point2D(0, 3);

        Point2D center = Circumcenter.of(a, b, c);

        assertEquals(2.0, center.x(), EPSILON);
        assertEquals(1.5, center.z(), EPSILON);
    }

    @Test
    void isEquidistantAcrossManyRandomNonDegenerateTriangles() {
        Random random = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            Point2D a = new Point2D(random.nextDouble() * 100, random.nextDouble() * 100);
            Point2D b = new Point2D(random.nextDouble() * 100, random.nextDouble() * 100);
            Point2D c = new Point2D(random.nextDouble() * 100, random.nextDouble() * 100);

            Point2D center = Circumcenter.of(a, b, c);
            double distanceA = distance(center, a);
            double distanceB = distance(center, b);
            double distanceC = distance(center, c);

            assertEquals(distanceA, distanceB, 1e-3, "trial " + trial);
            assertEquals(distanceB, distanceC, 1e-3, "trial " + trial);
        }
    }

    @Test
    void throwsOnExactlyCollinearPoints() {
        Point2D a = new Point2D(0, 0);
        Point2D b = new Point2D(1, 1);
        Point2D c = new Point2D(2, 2);

        assertThrows(IllegalArgumentException.class, () -> Circumcenter.of(a, b, c));
    }

    private static double distance(Point2D a, Point2D b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
