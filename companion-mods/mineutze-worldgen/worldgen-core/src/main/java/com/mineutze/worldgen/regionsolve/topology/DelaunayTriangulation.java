package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bowyer-Watson incremental Delaunay triangulation over 2D points.
 *
 * <p>The triangulation's edges are returned directly as the seed adjacency
 * graph: the Delaunay triangulation is the dual of the Voronoi diagram, so
 * two points share a Delaunay edge exactly when their Voronoi cells touch —
 * callers never need to construct the Voronoi diagram itself to get that
 * adjacency information (see doc/Modelisation/ActivityDiagram/
 * seed-graph-generation.puml).
 *
 * <p>Known limitation, not yet handled: three or more exactly collinear
 * input points have no valid circumcircle and are not supported. Not
 * defended against here — {@link SeedGrid}'s jittered-grid scatter makes
 * exact collinearity at floating-point precision practically unreachable,
 * and even if it occurred, the result only feeds an abstract graph that a
 * caller's reseed tier can treat as retriable.
 */
public final class DelaunayTriangulation {

    private static final double SUPER_TRIANGLE_MARGIN_FACTOR = 20.0;

    private DelaunayTriangulation() {
    }

    public static Set<NodeEdge> triangulate(List<Point2D> points) {
        if (points.size() < 2) {
            return Set.of();
        }
        if (points.size() == 2) {
            return Set.of(NodeEdge.of(0, 1));
        }

        BuiltTriangulation built = buildTriangulation(points);
        return extractEdges(built.faces(), built.superTriangleStart());
    }

    /**
     * Same triangulation as {@link #triangulate}, but returns the actual
     * faces instead of collapsing them into an adjacency edge set — needed
     * by anything treating the mesh as real geometry (merge/displacement),
     * not just an abstract graph. Fewer than 3 real points can't form a
     * face at all, so this returns an empty list in that case (unlike
     * {@code triangulate}, which still reports a degenerate single edge
     * for exactly 2 points).
     */
    public static List<IndexedTriangle> triangulateFaces(List<Point2D> points) {
        if (points.size() < 3) {
            return List.of();
        }

        BuiltTriangulation built = buildTriangulation(points);
        return built.faces().stream()
            .filter(triangle -> !touchesSuperTriangle(triangle, built.superTriangleStart()))
            .toList();
    }

    private static BuiltTriangulation buildTriangulation(List<Point2D> points) {
        List<Point2D> workingPoints = new ArrayList<>(points);
        int superTriangleStart = workingPoints.size();
        workingPoints.addAll(buildSuperTriangleVertices(points));

        List<IndexedTriangle> triangulation = new ArrayList<>();
        triangulation.add(orientedTriangle(superTriangleStart, superTriangleStart + 1, superTriangleStart + 2, workingPoints));

        for (int pointIndex = 0; pointIndex < points.size(); pointIndex++) {
            triangulation = insertPoint(triangulation, workingPoints, pointIndex);
        }

        return new BuiltTriangulation(triangulation, superTriangleStart);
    }

    private record BuiltTriangulation(List<IndexedTriangle> faces, int superTriangleStart) {
    }

    private static boolean touchesSuperTriangle(IndexedTriangle triangle, int superTriangleStart) {
        return triangle.a() >= superTriangleStart || triangle.b() >= superTriangleStart || triangle.c() >= superTriangleStart;
    }

    private static List<IndexedTriangle> insertPoint(List<IndexedTriangle> triangulation, List<Point2D> workingPoints, int pointIndex) {
        Point2D point = workingPoints.get(pointIndex);

        List<IndexedTriangle> badTriangles = new ArrayList<>();
        for (IndexedTriangle triangle : triangulation) {
            if (isInsideCircumcircle(triangle, workingPoints, point)) {
                badTriangles.add(triangle);
            }
        }

        Set<NodeEdge> boundaryPolygon = findBoundaryPolygon(badTriangles);

        List<IndexedTriangle> nextTriangulation = new ArrayList<>(triangulation);
        nextTriangulation.removeAll(badTriangles);
        for (NodeEdge edge : boundaryPolygon) {
            nextTriangulation.add(orientedTriangle(edge.a(), edge.b(), pointIndex, workingPoints));
        }
        return nextTriangulation;
    }

    /** Edges belonging to exactly one bad triangle are the hole's boundary — safe to re-triangulate through the new point. */
    private static Set<NodeEdge> findBoundaryPolygon(List<IndexedTriangle> badTriangles) {
        Map<NodeEdge, Integer> edgeOccurrences = new LinkedHashMap<>();
        for (IndexedTriangle triangle : badTriangles) {
            for (NodeEdge edge : triangle.edges()) {
                edgeOccurrences.merge(edge, 1, Integer::sum);
            }
        }

        Set<NodeEdge> boundary = new LinkedHashSet<>();
        edgeOccurrences.forEach((edge, occurrences) -> {
            if (occurrences == 1) {
                boundary.add(edge);
            }
        });
        return boundary;
    }

    private static Set<NodeEdge> extractEdges(List<IndexedTriangle> triangulation, int superTriangleStart) {
        Set<NodeEdge> edges = new LinkedHashSet<>();
        for (IndexedTriangle triangle : triangulation) {
            if (touchesSuperTriangle(triangle, superTriangleStart)) {
                continue;
            }
            edges.addAll(triangle.edges());
        }
        return edges;
    }

    private static List<Point2D> buildSuperTriangleVertices(List<Point2D> points) {
        double minX = points.stream().mapToDouble(Point2D::x).min().orElseThrow();
        double maxX = points.stream().mapToDouble(Point2D::x).max().orElseThrow();
        double minZ = points.stream().mapToDouble(Point2D::z).min().orElseThrow();
        double maxZ = points.stream().mapToDouble(Point2D::z).max().orElseThrow();

        double centerX = (minX + maxX) / 2.0;
        double centerZ = (minZ + maxZ) / 2.0;
        double span = Math.max(maxX - minX, maxZ - minZ) * SUPER_TRIANGLE_MARGIN_FACTOR + 1.0;

        return List.of(
            new Point2D(centerX - span, centerZ - span),
            new Point2D(centerX + span, centerZ - span),
            new Point2D(centerX, centerZ + span)
        );
    }

    /** The in-circle determinant test below only works for a consistently-wound triangle, so every triangle is built through here. */
    private static IndexedTriangle orientedTriangle(int a, int b, int c, List<Point2D> workingPoints) {
        if (signedArea(workingPoints.get(a), workingPoints.get(b), workingPoints.get(c)) < 0) {
            return new IndexedTriangle(a, c, b);
        }
        return new IndexedTriangle(a, b, c);
    }

    private static double signedArea(Point2D a, Point2D b, Point2D c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
    }

    /** Standard in-circle determinant test for a counter-clockwise-wound triangle ABC and query point P: positive means P is inside ABC's circumcircle. */
    private static boolean isInsideCircumcircle(IndexedTriangle triangle, List<Point2D> workingPoints, Point2D point) {
        Point2D a = workingPoints.get(triangle.a());
        Point2D b = workingPoints.get(triangle.b());
        Point2D c = workingPoints.get(triangle.c());

        double ax = a.x() - point.x();
        double az = a.z() - point.z();
        double bx = b.x() - point.x();
        double bz = b.z() - point.z();
        double cx = c.x() - point.x();
        double cz = c.z() - point.z();

        double determinant =
              (ax * ax + az * az) * (bx * cz - cx * bz)
            - (bx * bx + bz * bz) * (ax * cz - cx * az)
            + (cx * cx + cz * cz) * (ax * bz - bx * az);

        return determinant > 0;
    }
}
