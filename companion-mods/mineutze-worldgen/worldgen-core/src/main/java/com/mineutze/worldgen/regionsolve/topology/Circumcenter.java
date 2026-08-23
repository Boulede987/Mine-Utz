package com.mineutze.worldgen.regionsolve.topology;

/**
 * The center of the circle passing through 3 points — the standard
 * corner-point construction for a Voronoi diagram built as the dual of a
 * Delaunay triangulation (each triangle's circumcenter is a Voronoi vertex,
 * shared by the 3 nodes at that triangle's corners). Pure computational
 * geometry, no world-gen domain knowledge, same spirit as {@link
 * DelaunayTriangulation}/{@link Point2D} living in this package.
 *
 * <p>Known limitation, not yet handled, mirroring {@link
 * DelaunayTriangulation}'s own documented stance on exact collinearity: 3
 * exactly collinear points have no circumcenter (the denominator below is
 * exactly zero). Not defended against here for the same reason
 * {@code DelaunayTriangulation} doesn't defend against it — a jittered
 * scatter makes exact collinearity practically unreachable at floating-point
 * precision.
 */
public final class Circumcenter {

    private Circumcenter() {
    }

    public static Point2D of(Point2D a, Point2D b, Point2D c) {
        double denominator = 2.0 * (a.x() * (b.z() - c.z()) + b.x() * (c.z() - a.z()) + c.x() * (a.z() - b.z()));
        if (denominator == 0.0) {
            throw new IllegalArgumentException("Circumcenter.of: points " + a + ", " + b + ", " + c + " are exactly collinear, no circumcenter exists");
        }

        double aSq = a.x() * a.x() + a.z() * a.z();
        double bSq = b.x() * b.x() + b.z() * b.z();
        double cSq = c.x() * c.x() + c.z() * c.z();

        double centerX = (aSq * (b.z() - c.z()) + bSq * (c.z() - a.z()) + cSq * (a.z() - b.z())) / denominator;
        double centerZ = (aSq * (c.x() - b.x()) + bSq * (a.x() - c.x()) + cSq * (b.x() - a.x())) / denominator;
        return new Point2D(centerX, centerZ);
    }
}
