package com.mineutze.worldgen.regionsolve.topology;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexMeshBuilderTest {

    private static final double SIZE = 200;

    /**
     * The whole reason a literal hex lattice (over a Voronoi-dual alternative) was
     * chosen: face-degree-6 exact by construction, not just empirically likely. Checked
     * against a real-scale lattice, mirroring {@code TriangleAdjacencyTest}'s own
     * "real seed-grid triangulation, not a toy case" discipline.
     */
    @Test
    void everyNonHullTouchingFaceHasExactlySixNeighbors() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 6000, 6000);
        assertTrue(mesh.faces().size() > 20, "expected a real-sized lattice to exercise this on, got " + mesh.faces().size() + " faces");

        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                continue;
            }
            assertEquals(6, adjacency.neighborFaceIndices().get(faceIndex).size(), "face " + faceIndex + " has fewer than 6 neighbors despite not being hull-touching");
        }
    }

    /**
     * The property this whole pivot depends on: with only 3 faces ever meeting at a
     * point, every pair among them shares an edge too — so CSP-time edge legality
     * checking is complete, no vertex-only touch can slip through. Checked directly
     * against real geometry (vertex-to-face incidence), not just argued abstractly.
     */
    @Test
    void everyFullyInteriorVertexHasExactlyThreeIncidentFaces() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 6000, 6000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Set<Integer> hullTouching = adjacency.hullTouchingFaceIndices();

        Map<Integer, Set<Integer>> facesByVertex = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            for (int vertexIndex : mesh.faces().get(faceIndex).vertexIndices()) {
                facesByVertex.computeIfAbsent(vertexIndex, key -> new HashSet<>()).add(faceIndex);
            }
        }

        boolean checkedAnyInteriorVertex = false;
        for (Map.Entry<Integer, Set<Integer>> entry : facesByVertex.entrySet()) {
            Set<Integer> incidentFaces = entry.getValue();
            boolean touchesHull = incidentFaces.stream().anyMatch(hullTouching::contains);
            if (touchesHull) {
                continue; // missing lattice beyond the padded window, not a real violation - same discipline as a triangle's hull-touching face
            }
            checkedAnyInteriorVertex = true;
            assertEquals(3, incidentFaces.size(), "vertex " + entry.getKey() + " incident to " + incidentFaces.size() + " faces, expected exactly 3");
        }
        assertTrue(checkedAnyInteriorVertex, "expected at least one fully-interior vertex to check");
    }

    @Test
    void cornersAreDedupedAcrossAdjacentFaces() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 4000, 4000);
        assertTrue(mesh.vertices().size() < mesh.faces().size() * 6, "expected shared corners to be pooled, not one copy per face");
    }

    /** Two mesh-adjacent faces must share exactly 2 vertex INDICES (their common edge's endpoints), not just 2 equal-valued positions computed independently. */
    @Test
    void adjacentFacesShareExactlyTwoVertexIndices() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 4000, 4000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());

        boolean checkedAnyPair = false;
        for (Map.Entry<Integer, Set<Integer>> entry : adjacency.neighborFaceIndices().entrySet()) {
            int faceIndex = entry.getKey();
            for (int neighborIndex : entry.getValue()) {
                if (neighborIndex < faceIndex) {
                    continue; // check each unordered pair once
                }
                Set<Integer> ownVertices = new HashSet<>(mesh.faces().get(faceIndex).vertexIndices());
                Set<Integer> neighborVertices = new HashSet<>(mesh.faces().get(neighborIndex).vertexIndices());
                ownVertices.retainAll(neighborVertices);
                assertEquals(2, ownVertices.size(), "faces " + faceIndex + " and " + neighborIndex + " are adjacent but share " + ownVertices.size() + " vertex indices, expected exactly 2");
                checkedAnyPair = true;
            }
        }
        assertTrue(checkedAnyPair, "expected at least one adjacent face pair to check");
    }

    @Test
    void sameQueryTwiceProducesTheSameMesh() {
        HexMeshBuilder.HexMesh first = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        HexMeshBuilder.HexMesh second = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        assertEquals(first, second);
    }

    /**
     * The property a global face-keyed cache depends on: a face's {@code axialPos} is a
     * stable, bounding-box-independent identity, and its own corner GEOMETRY (not just its
     * key) must also come out the SAME physical point regardless of which of two different,
     * only partially-overlapping windows built it - two independent solve batches touching
     * the same physical face must agree on it, not merely reference "the same index."
     *
     * <p>Tolerance, not exact equality: which of two mathematically-identical but
     * independently-computed corner candidates becomes the pooled canonical entry (see
     * {@code HexMeshBuilder.vertexIndexFor}'s own {@code DEDUP_PRECISION}) depends on scan
     * ORDER, which differs between these two bounding boxes - same floating-point-noise
     * ceiling {@code DEDUP_PRECISION} (1e-6 blocks) already establishes as "identical" for
     * this exact reason, confirmed directly here (~1e-13, many orders of magnitude under
     * it) rather than assumed.
     */
    @Test
    void sameAxialPosAcrossDifferentBoundingBoxesIsTheSamePhysicalFace() {
        HexMeshBuilder.HexMesh small = HexMeshBuilder.build(SIZE, 0, 0, 2000, 2000);
        HexMeshBuilder.HexMesh large = HexMeshBuilder.build(SIZE, -1000, -1000, 3000, 3000);

        Map<HexGrid.AxialPos, HexFace> facesInSmall = new HashMap<>();
        for (HexFace face : small.faces()) {
            facesInSmall.put(face.axialPos(), face);
        }

        boolean checkedAny = false;
        for (HexFace largeFace : large.faces()) {
            HexFace smallFace = facesInSmall.get(largeFace.axialPos());
            if (smallFace == null) {
                continue;
            }
            checkedAny = true;
            List<Point2D> smallCorners = smallFace.vertexIndices().stream().map(small.vertices()::get).toList();
            List<Point2D> largeCorners = largeFace.vertexIndices().stream().map(large.vertices()::get).toList();
            for (int i = 0; i < smallCorners.size(); i++) {
                Point2D a = smallCorners.get(i);
                Point2D b = largeCorners.get(i);
                double distance = Math.hypot(a.x() - b.x(), a.z() - b.z());
                assertTrue(distance < 1.0e-6, "face " + largeFace.axialPos() + " corner " + i + " differs by " + distance + " blocks depending on which bounding box built it");
            }
        }
        assertTrue(checkedAny, "expected at least one face shared between both bounding boxes");
    }
}
