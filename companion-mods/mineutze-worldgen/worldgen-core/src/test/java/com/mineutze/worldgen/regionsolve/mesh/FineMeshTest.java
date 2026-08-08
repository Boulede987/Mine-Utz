package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.regionsolve.topology.NodeEdge;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FineMeshTest {

    @Test
    void singleTriangleHasNoEdgeAdjacentFacesAndEveryEdgeIsAMeshBoundary() {
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(0, 0), new Point2D(10, 0), new Point2D(5, 10)
        ));

        assertEquals(1, mesh.faces().size());
        assertEquals(List.of(), mesh.edgeAdjacentFaces(0));
        assertEquals(3, mesh.edges().size());
        mesh.edges().forEach(edge -> assertTrue(edge.isMeshBoundary(), edge + " should be a mesh boundary edge"));
        assertEquals(List.of(0), mesh.incidentFaces(0));
        assertEquals(List.of(0), mesh.incidentFaces(1));
        assertEquals(List.of(0), mesh.incidentFaces(2));
    }

    @Test
    void twoTrianglesSharingADiagonalAreEachOthersOnlyEdgeNeighbor() {
        // Same fixture as DelaunayTriangulationTest's "general quadrilateral" case:
        // deliberately irregular so exactly one diagonal is picked, no ambiguity.
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(0, 0), new Point2D(10, 0), new Point2D(10, 10), new Point2D(0, 7)
        ));

        assertEquals(2, mesh.faces().size());
        assertEquals(5, mesh.edges().size(), "4 boundary edges + exactly 1 shared diagonal");

        long interiorEdgeCount = mesh.edges().stream().filter(edge -> edge.isInterior()).count();
        assertEquals(1, interiorEdgeCount, "exactly one diagonal is interior, the rest are the quad's own boundary");

        NodeEdge diagonal = mesh.edges().stream().filter(edge -> edge.isInterior()).findFirst().orElseThrow().vertices();
        assertEquals(Set.of(0, 1), Set.copyOf(mesh.edgeBetween(diagonal.a(), diagonal.b()).faceIds()));

        // With only 2 faces total, sharing one edge makes them each other's sole edge-adjacent face.
        assertEquals(List.of(1), mesh.edgeAdjacentFaces(0));
        assertEquals(List.of(0), mesh.edgeAdjacentFaces(1));

        // The diagonal's own two endpoints are the only vertices touching both faces.
        assertEquals(Set.of(0, 1), Set.copyOf(mesh.incidentFaces(diagonal.a())));
        assertEquals(Set.of(0, 1), Set.copyOf(mesh.incidentFaces(diagonal.b())));
        int nonDiagonalVertexCount = 0;
        for (int vertexId = 0; vertexId < 4; vertexId++) {
            if (vertexId != diagonal.a() && vertexId != diagonal.b()) {
                assertEquals(List.of(mesh.incidentFaces(vertexId).get(0)), mesh.incidentFaces(vertexId));
                assertEquals(1, mesh.incidentFaces(vertexId).size(), "a non-diagonal corner only ever belongs to one face");
                nonDiagonalVertexCount++;
            }
        }
        assertEquals(2, nonDiagonalVertexCount);
    }

    @Test
    void edgeBetweenReturnsTheSameObjectRegardlessOfVertexArgumentOrder() {
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(0, 0), new Point2D(10, 0), new Point2D(5, 10)
        ));

        assertEquals(mesh.edgeBetween(0, 1), mesh.edgeBetween(1, 0));
    }

    private static List<ScatteredPoint> uncoloredScatter(Point2D... positions) {
        return List.of(positions).stream()
            .map(position -> new ScatteredPoint(position, Optional.empty()))
            .toList();
    }
}
