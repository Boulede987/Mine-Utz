package com.mineutze.worldgen.regionsolve.topology.mesh;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceAdjacencyTest {

    /** Margin-free mesh's perimeter faces MUST be hull-touching - whole reason real caller adds margin (queried wider than box it cares about) is to push hull-touching faces past box it trusts. */
    @Test
    void perimeterFacesAreHullTouching() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(200, 0, 0, 3000, 3000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        assertFalse(adjacency.hullTouchingFaceIndices().isEmpty(), "a bounded point set must have SOME hull-touching faces");
    }

    /** Box too small to fit second hex center forms exactly one face with zero neighbors, fully hull-touching. */
    @Test
    void aSingleFaceHasNoNeighborsAndIsFullyHullTouching() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(10000, 0, 0, 1, 1);
        assertEquals(1, mesh.faces().size());
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        assertTrue(adjacency.neighborFaceIndices().get(0).isEmpty());
        assertTrue(adjacency.hullTouchingFaceIndices().contains(0));
    }

    @Test
    void neighborRelationIsSymmetric() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(200, 0, 0, 4000, 4000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        adjacency.neighborFaceIndices().forEach((faceIndex, neighbors) ->
            neighbors.forEach(neighbor ->
                assertTrue(adjacency.neighborFaceIndices().get(neighbor).contains(faceIndex), "face " + neighbor + " doesn't list " + faceIndex + " back as a neighbor")
            )
        );
    }

    /**
     * Shared-edge-grouping algorithm caps neighbors at 6 for real hex mesh (only
     * production shape today) - {@code IndexedTriangle}/{@code DelaunayTriangulation}
     * (moved to triangle-specific fixture, then deleted entirely per user doubt about
     * maintaining second mesh-generation algorithm solely for tests - see git history)
     * used to also exercise 3-neighbor triangle mesh here; production never
     * triangulates, so that coverage tested retired design assumption, not real
     * behavior.
     */
    @Test
    void worksOverHexFacesToo() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(200, 0, 0, 6000, 6000);
        assertTrue(mesh.faces().size() > 20, "expected a real-sized lattice, got " + mesh.faces().size() + " faces");

        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            Set<Integer> neighbors = adjacency.neighborFaceIndices().get(faceIndex);
            assertTrue(neighbors.size() <= 6, "face " + faceIndex + " has " + neighbors.size() + " neighbors, expected at most 6");
            assertFalse(neighbors.contains(faceIndex));
        }
    }

    @Test
    void sharedVertexIndicesOfReturnsExactlyTheTwoRealSharedVertices() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(200, 0, 0, 6000, 6000);
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());

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

        int[] shared = FaceAdjacency.sharedVertexIndicesOf(mesh, faceA, faceB);
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
        FaceAdjacency.Adjacency adjacency = FaceAdjacency.of(mesh.faces());

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
        assertThrows(IllegalStateException.class, () -> FaceAdjacency.sharedVertexIndicesOf(mesh, faceA, finalFaceB));
    }
}
