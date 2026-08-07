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
