package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangleAdjacencyTest {

    /**
     * The whole reason triangles were chosen over squares/other shapes (doc/DESIGN.md):
     * caps how many other biomes any one placement can ever be adjacent to. Checked
     * against a real seed-grid triangulation, not just a hand-built minimal example, so a
     * real-scale scatter is what's actually being proven, not a toy case.
     */
    @Test
    void everyFaceHasAtMostThreeNeighbors() {
        List<Point2D> points = SeedGrid.seedsIn(2026L, 300, 0, 0, 6000, 6000);
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);
        assertTrue(faces.size() > 20, "expected a real-sized triangulation to exercise this on, got " + faces.size() + " faces");

        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(faces);
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            Set<Integer> neighbors = adjacency.neighborFaceIndices().get(faceIndex);
            assertTrue(neighbors.size() <= 3, "face " + faceIndex + " has " + neighbors.size() + " neighbors, expected at most 3");
        }
    }

    @Test
    void aFaceIsNeverItsOwnNeighbor() {
        List<Point2D> points = SeedGrid.seedsIn(3L, 300, 0, 0, 4000, 4000);
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(faces);
        for (int faceIndex = 0; faceIndex < faces.size(); faceIndex++) {
            assertFalse(adjacency.neighborFaceIndices().get(faceIndex).contains(faceIndex));
        }
    }

    @Test
    void neighborRelationIsSymmetric() {
        List<Point2D> points = SeedGrid.seedsIn(4L, 300, 0, 0, 4000, 4000);
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(faces);
        adjacency.neighborFaceIndices().forEach((faceIndex, neighbors) ->
            neighbors.forEach(neighbor ->
                assertTrue(adjacency.neighborFaceIndices().get(neighbor).contains(faceIndex), "face " + neighbor + " doesn't list " + faceIndex + " back as a neighbor")
            )
        );
    }

    /** A margin-free triangulation's perimeter faces MUST be hull-touching - the whole reason a real caller adds margin (SeedGrid queried wider than the box it actually cares about) is to push hull-touching faces out past the box it trusts. */
    @Test
    void perimeterFacesAreHullTouching() {
        List<Point2D> points = SeedGrid.seedsIn(5L, 300, 0, 0, 3000, 3000);
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(faces);
        assertFalse(adjacency.hullTouchingFaceIndices().isEmpty(), "a bounded point set must have SOME hull-touching faces");
    }

    /** Three points form exactly one face with zero neighbors, all three of its edges on the hull. */
    @Test
    void aSingleTriangleHasNoNeighborsAndIsFullyHullTouching() {
        List<Point2D> points = List.of(new Point2D(0, 0), new Point2D(10, 0), new Point2D(0, 10));
        List<IndexedTriangle> faces = DelaunayTriangulation.triangulateFaces(points);
        assertEquals(1, faces.size());
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(faces);
        assertTrue(adjacency.neighborFaceIndices().get(0).isEmpty());
        assertTrue(adjacency.hullTouchingFaceIndices().contains(0));
    }

    /**
     * The shared-edge-grouping algorithm is shape-agnostic - it works over a {@link
     * HexFace} mesh exactly the same way, just with a higher (6, not 3) neighbor cap,
     * confirming the generalization from {@code List<IndexedTriangle>} to
     * {@code List<? extends MeshFace>} didn't accidentally bake in a triangle assumption.
     */
    @Test
    void worksOverHexFacesToo() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(200, 0, 0, 6000, 6000);
        assertTrue(mesh.faces().size() > 20, "expected a real-sized lattice, got " + mesh.faces().size() + " faces");

        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            Set<Integer> neighbors = adjacency.neighborFaceIndices().get(faceIndex);
            assertTrue(neighbors.size() <= 6, "face " + faceIndex + " has " + neighbors.size() + " neighbors, expected at most 6");
            assertFalse(neighbors.contains(faceIndex));
        }
    }

    @Test
    void sharedVertexIndicesOfReturnsExactlyTheTwoRealSharedVertices() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(200, 0, 0, 6000, 6000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());

        int faceA = -1;
        int faceB = -1;
        outer:
        for (int candidateA = 0; candidateA < mesh.faces().size(); candidateA++) {
            for (int candidateB : adjacency.neighborFaceIndices().get(candidateA)) {
                faceA = candidateA;
                faceB = candidateB;
                break outer;
            }
        }
        assertTrue(faceA >= 0, "expected a real-sized lattice to have at least one adjacent face pair");

        int[] shared = TriangleAdjacency.sharedVertexIndicesOf(mesh, faceA, faceB);
        assertEquals(2, shared.length);
        Set<Integer> verticesA = Set.copyOf(mesh.faces().get(faceA).vertexIndices());
        Set<Integer> verticesB = Set.copyOf(mesh.faces().get(faceB).vertexIndices());
        for (int vertexIndex : shared) {
            assertTrue(verticesA.contains(vertexIndex), "returned vertex " + vertexIndex + " isn't even one of face " + faceA + "'s own corners");
            assertTrue(verticesB.contains(vertexIndex), "returned vertex " + vertexIndex + " isn't even one of face " + faceB + "'s own corners");
        }
    }

    @Test
    void sharedVertexIndicesOfThrowsForNonAdjacentFaces() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(200, 0, 0, 6000, 6000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());

        int faceA = 0;
        int faceB = -1;
        for (int candidate = 0; candidate < mesh.faces().size(); candidate++) {
            if (candidate != faceA && !adjacency.neighborFaceIndices().get(faceA).contains(candidate)) {
                faceB = candidate;
                break;
            }
        }
        assertTrue(faceB >= 0, "expected a real-sized lattice to have at least one non-adjacent face pair");

        int finalFaceB = faceB;
        assertThrows(IllegalStateException.class, () -> TriangleAdjacency.sharedVertexIndicesOf(mesh, faceA, finalFaceB));
    }
}
