package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelaunayTriangulationTest {

    @Test
    void twoPointsProduceOneEdge() {
        List<Point2D> points = List.of(new Point2D(0, 0), new Point2D(10, 0));

        Set<NodeEdge> edges = DelaunayTriangulation.triangulate(points);

        assertEquals(Set.of(NodeEdge.of(0, 1)), edges);
    }

    @Test
    void threePointsProduceATriangle() {
        List<Point2D> points = List.of(new Point2D(0, 0), new Point2D(10, 0), new Point2D(5, 10));

        Set<NodeEdge> edges = DelaunayTriangulation.triangulate(points);

        assertEquals(Set.of(NodeEdge.of(0, 1), NodeEdge.of(1, 2), NodeEdge.of(0, 2)), edges);
    }

    @Test
    void fourPointsInAGeneralQuadrilateralProduceExactlyOneDiagonal() {
        // Deliberately irregular (not a square) so the correct diagonal isn't
        // ambiguous from floating-point cocircularity.
        List<Point2D> points = List.of(
            new Point2D(0, 0),
            new Point2D(10, 0),
            new Point2D(10, 10),
            new Point2D(0, 7)
        );

        Set<NodeEdge> edges = DelaunayTriangulation.triangulate(points);

        // 4 boundary edges + exactly 1 diagonal = 5, never both diagonals (6).
        assertEquals(5, edges.size());
        assertTrue(edges.contains(NodeEdge.of(0, 1)));
        assertTrue(edges.contains(NodeEdge.of(1, 2)));
        assertTrue(edges.contains(NodeEdge.of(2, 3)));
        assertTrue(edges.contains(NodeEdge.of(0, 3)));
    }

    @Test
    void everyPointHasAtLeastOneEdge() {
        List<Point2D> points = jitteredGridScatter(6, 6, 42);

        Set<NodeEdge> edges = DelaunayTriangulation.triangulate(points);

        Set<Integer> connectedPoints = new HashSet<>();
        edges.forEach(edge -> {
            connectedPoints.add(edge.a());
            connectedPoints.add(edge.b());
        });
        assertEquals(points.size(), connectedPoints.size(), "every input point must appear in at least one edge");
    }

    @Test
    void edgeCountStaysWithinThePlanarTriangulationBound() {
        // Euler's formula bound for any planar triangulation of n >= 3 points: edges <= 3n - 6.
        List<Point2D> points = jitteredGridScatter(8, 8, 7);

        Set<NodeEdge> edges = DelaunayTriangulation.triangulate(points);

        int maxPossibleEdges = 3 * points.size() - 6;
        assertTrue(edges.size() <= maxPossibleEdges,
            "edge count " + edges.size() + " exceeds planar bound " + maxPossibleEdges);
    }

    @Test
    void fewerThanThreePointsProduceNoFaces() {
        assertEquals(List.of(), DelaunayTriangulation.triangulateFaces(List.of()));
        assertEquals(List.of(), DelaunayTriangulation.triangulateFaces(List.of(new Point2D(0, 0))));
        assertEquals(List.of(), DelaunayTriangulation.triangulateFaces(List.of(new Point2D(0, 0), new Point2D(10, 0))));
    }

    @Test
    void threePointsProduceExactlyOneFace() {
        List<Point2D> points = List.of(new Point2D(0, 0), new Point2D(10, 0), new Point2D(5, 10));

        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);

        assertEquals(1, faces.size());
        assertTrue(faces.get(0).hasVertex(0) && faces.get(0).hasVertex(1) && faces.get(0).hasVertex(2));
    }

    @Test
    void quadrilateralFacesSumToItsExactArea() {
        // A square: shoelace area is exactly 100 regardless of which diagonal gets picked.
        List<Point2D> points = List.of(
            new Point2D(0, 0),
            new Point2D(10, 0),
            new Point2D(10, 10),
            new Point2D(0, 10)
        );

        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);

        assertEquals(2, faces.size());
        double totalArea = faces.stream().mapToDouble(triangle -> triangleArea(triangle, points)).sum();
        assertEquals(100.0, totalArea, 1e-9);
    }

    @Test
    void everyTriangulateEdgeAppearsInOneOrTwoTriangulateFacesFaces() {
        List<Point2D> points = jitteredGridScatter(6, 6, 42);

        Set<NodeEdge> edges = DelaunayTriangulation.triangulate(points);
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);

        for (NodeEdge edge : edges) {
            long owningFaces = faces.stream().filter(triangle -> triangle.edges().contains(edge)).count();
            assertTrue(owningFaces == 1 || owningFaces == 2,
                "edge " + edge + " owned by " + owningFaces + " faces, expected 1 or 2");
        }
    }

    private static double triangleArea(IndexedTriangle triangle, List<Point2D> points) {
        Point2D a = points.get(triangle.a());
        Point2D b = points.get(triangle.b());
        Point2D c = points.get(triangle.c());
        return Math.abs((b.x() - a.x()) * (c.z() - a.z()) - (c.x() - a.x()) * (b.z() - a.z())) / 2.0;
    }

    private static List<Point2D> jitteredGridScatter(int columns, int rows, long seed) {
        Random random = new Random(seed);
        return java.util.stream.IntStream.range(0, columns * rows)
            .mapToObj(index -> {
                int gridX = index % columns;
                int gridZ = index / columns;
                return new Point2D(gridX * 10.0 + random.nextDouble() * 4.0, gridZ * 10.0 + random.nextDouble() * 4.0);
            })
            .toList();
    }
}
