package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.regionsolve.topology.Point2D;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshDiagonalPairsTest {

    // Every fixture below is "1 interior point + N convex hull points around
    // it, all irregular (no two points equidistant/cocircular)" - a point
    // set shaped this way has exactly one valid triangulation at all
    // (Delaunay or otherwise): a fan connecting the interior point to every
    // hull point in cyclic order. That forces vertex 0 (the interior point,
    // always listed first so its id is predictable) to have degree exactly
    // N, letting the expected point-only pair count be worked out by hand
    // instead of guessed at.

    @Test
    void degreeFourFanHasExactlyTwoPointOnlyPairsAtTheCenter() {
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(0, 0),   // 0: interior
            new Point2D(10, 1),  // 1
            new Point2D(1, 9),   // 2
            new Point2D(-11, -2),// 3
            new Point2D(-2, -8)  // 4
        ));

        assertEquals(4, mesh.incidentFaces(0).size(), "interior point must be a vertex of all 4 fan triangles");

        List<FacePair> pointOnlyPairs = MeshDiagonalPairs.pointOnlyPairsAt(mesh, 0);

        // C(4,2) = 6 total pairs among 4 fan triangles, 4 of which are the
        // cyclically-consecutive (edge-adjacent) ones - exactly 2 left over.
        assertEquals(2, pointOnlyPairs.size());
        pointOnlyPairs.forEach(pair -> assertFalse(
            mesh.edgeAdjacentFaces(pair.a()).contains(pair.b()),
            pair + " was reported as point-only but is actually edge-adjacent"
        ));
    }

    @Test
    void degreeSixFanHasExactlyNinePointOnlyPairsAtTheCenter() {
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(0, 0),     // 0: interior
            new Point2D(12, 1),    // 1
            new Point2D(6, 10),    // 2
            new Point2D(-6, 9),    // 3
            new Point2D(-12, -1),  // 4
            new Point2D(-5, -10),  // 5
            new Point2D(7, -9)     // 6
        ));

        assertEquals(6, mesh.incidentFaces(0).size(), "interior point must be a vertex of all 6 fan triangles");

        List<FacePair> pointOnlyPairs = MeshDiagonalPairs.pointOnlyPairsAt(mesh, 0);

        // C(6,2) = 15 total pairs among 6 fan triangles, 6 of which are the
        // cyclically-consecutive (edge-adjacent) ones - 9 left over.
        assertEquals(9, pointOnlyPairs.size());
        pointOnlyPairs.forEach(pair -> assertFalse(
            mesh.edgeAdjacentFaces(pair.a()).contains(pair.b()),
            pair + " was reported as point-only but is actually edge-adjacent"
        ));
    }

    @Test
    void singleTriangleHasNoPointOnlyPairsAnywhere() {
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(0, 0), new Point2D(10, 0), new Point2D(5, 10)
        ));

        for (int vertexId = 0; vertexId < 3; vertexId++) {
            assertTrue(MeshDiagonalPairs.pointOnlyPairsAt(mesh, vertexId).isEmpty());
        }
        assertTrue(MeshDiagonalPairs.diagonalPartnersOf(mesh, 0).isEmpty());
    }

    @Test
    void diagonalPartnersOfAFaceMatchesThePairwiseViewAtEachOfItsVertices() {
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(0, 0), new Point2D(10, 1), new Point2D(1, 9), new Point2D(-11, -2), new Point2D(-2, -8)
        ));

        // Whichever fan triangle contains the interior vertex (0) as a
        // corner, its diagonal partner set must be exactly the faces that
        // showed up as its point-only pair partner in pointOnlyPairsAt.
        List<FacePair> pairsAtCenter = MeshDiagonalPairs.pointOnlyPairsAt(mesh, 0);
        int someFanFace = pairsAtCenter.get(0).a();
        Set<Integer> expectedPartners = pairsAtCenter.stream()
            .filter(pair -> pair.a() == someFanFace || pair.b() == someFanFace)
            .map(pair -> pair.a() == someFanFace ? pair.b() : pair.a())
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedPartners, MeshDiagonalPairs.diagonalPartnersOf(mesh, someFanFace));
    }

    private static List<ScatteredPoint> uncoloredScatter(Point2D... positions) {
        return List.of(positions).stream()
            .map(position -> new ScatteredPoint(position, Optional.empty()))
            .toList();
    }
}
