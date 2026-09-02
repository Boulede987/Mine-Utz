package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedVertexPlacementTest {

    private static final double SIZE = 200;
    private static final BiomeId BIOME = new BiomeId("test", "a");

    /**
     * The class doc's own central claim, checked directly rather than trusted: when every
     * face gets an IDENTICAL weight (a uniform target width matching the mesh's own raw
     * circumradius exactly - a real hexagon's side length equals its circumradius, so
     * width = 2 x SIZE reproduces the undisplaced footprint exactly), the radical center
     * of every mesh-vertex triple must reduce to the ordinary circumcenter of the 3 face
     * centers - which, for a regular hex lattice, IS the original undisplaced vertex.
     * This is what makes the power-diagram construction provably subsume the old
     * "nothing to correct" baseline, not just approximate it.
     */
    @Test
    void uniformWeightsReproduceTheOriginalUndisplacedVertices() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> uniformAssignments = uniformAssignments(mesh, adjacency, (int) (2 * SIZE));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, uniformAssignments, SIZE);
        assertTrue(displaced.size() > 10, "expected a real number of resolved mesh vertices, got " + displaced.size());

        for (Map.Entry<Integer, Point2D> entry : displaced.entrySet()) {
            Point2D original = mesh.vertices().get(entry.getKey());
            Point2D computed = entry.getValue();
            assertEquals(original.x(), computed.x(), 1e-6, "vertex " + entry.getKey() + " x drifted with uniform weights");
            assertEquals(original.z(), computed.z(), 1e-6, "vertex " + entry.getKey() + " z drifted with uniform weights");
        }
    }

    /**
     * The whole point of switching to a power diagram: a face targeting a much bigger
     * width than its neighbors must end up with MORE territory, meaning its own boundary
     * vertices sit FARTHER from its own center than the undisplaced baseline - the
     * opposite of the old per-edge bias formula, which produced exactly zero correction
     * whenever neighbors didn't happen to want the opposite direction.
     */
    @Test
    void aFaceTargetingMuchMoreAreaPushesItsBoundaryFartherFromItsOwnCenter() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int[] triangle = anyRealTriangle(mesh, adjacency);
        int bigFace = triangle[0];

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = uniformAssignments(mesh, adjacency, (int) (2 * SIZE));
        assignments.put(bigFace, new TriangleIdentitySizeAssignment.Assignment(BIOME, (int) (2 * SIZE * 3)));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE);
        Point2D bigFaceCenter = centroidOf(mesh, bigFace);

        boolean foundARealShift = false;
        for (int vertexIndex : mesh.faces().get(bigFace).vertexIndices()) {
            Point2D original = mesh.vertices().get(vertexIndex);
            Point2D computed = displaced.get(vertexIndex);
            if (computed == null) {
                continue; // an outer-hull vertex with no real mesh-vertex triple, not this test's concern
            }
            double originalDistance = distance(bigFaceCenter, original);
            double newDistance = distance(bigFaceCenter, computed);
            if (newDistance > originalDistance + 1e-6) {
                foundARealShift = true;
            }
        }
        assertTrue(foundARealShift, "expected at least one of the oversized face's own boundary vertices to move farther from its own center");
    }

    /** Mirrors the old per-edge bias clamp's own test intent: an extreme weight mismatch must still keep every displaced vertex within a bounded distance of its own undisplaced position, never producing a self-intersecting polygon regardless of how large the mismatch requests. */
    @Test
    void extremeWeightMismatchStaysClampedToASimplePolygon() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int[] triangle = anyRealTriangle(mesh, adjacency);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = uniformAssignments(mesh, adjacency, (int) (2 * SIZE));
        assignments.put(triangle[0], new TriangleIdentitySizeAssignment.Assignment(BIOME, 100_000));
        assignments.put(triangle[1], new TriangleIdentitySizeAssignment.Assignment(BIOME, 1));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE);
        double maxAllowedDisplacement = 0.4 * SIZE;
        for (int vertexIndex : mesh.faces().get(triangle[0]).vertexIndices()) {
            Point2D computed = displaced.get(vertexIndex);
            if (computed == null) {
                continue;
            }
            Point2D original = mesh.vertices().get(vertexIndex);
            assertTrue(
                distance(original, computed) <= maxAllowedDisplacement + 1e-6,
                "vertex " + vertexIndex + " displaced past the self-intersection-safe clamp"
            );
        }
    }

    /** A face with no assignment at all (fixed cross-region, or hull-touching) must not crash the computation - it falls back to a baseline weight, same as documented. */
    @Test
    void unassignedFacesFallBackToBaselineWeightWithoutCrashing() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int[] triangle = anyRealTriangle(mesh, adjacency);

        // Only ONE of the 3 faces in this triangle has a real assignment - the other two are unassigned (simulating fixed/hull-touching context).
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(triangle[0], new TriangleIdentitySizeAssignment.Assignment(BIOME, (int) (2 * SIZE)));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE);
        assertTrue(displaced.containsKey(sharedVertexOf(mesh, triangle)), "expected the shared vertex to still resolve using baseline weight for the unassigned faces");
    }

    private static Map<Integer, TriangleIdentitySizeAssignment.Assignment> uniformAssignments(
        HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency, int widthBlocks
    ) {
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            if (!adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                assignments.put(faceIndex, new TriangleIdentitySizeAssignment.Assignment(BIOME, widthBlocks));
            }
        }
        return assignments;
    }

    private static int[] anyRealTriangle(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency) {
        Set<Integer> allFaces = new HashSet<>();
        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            allFaces.add(faceIndex);
        }
        for (int[] triangle : adjacency.mutuallyAdjacentTriples(allFaces)) {
            boolean anyHullTouching = false;
            for (int face : triangle) {
                if (adjacency.hullTouchingFaceIndices().contains(face)) {
                    anyHullTouching = true;
                }
            }
            if (!anyHullTouching) {
                return triangle;
            }
        }
        throw new IllegalStateException("expected at least one fully-interior real mesh-vertex triple in a real-sized mesh");
    }

    private static int sharedVertexOf(HexMeshBuilder.HexMesh mesh, int[] triangle) {
        Set<Integer> shared = new HashSet<>(mesh.faces().get(triangle[0]).vertexIndices());
        shared.retainAll(mesh.faces().get(triangle[1]).vertexIndices());
        shared.retainAll(mesh.faces().get(triangle[2]).vertexIndices());
        return shared.iterator().next();
    }

    private static Point2D centroidOf(HexMeshBuilder.HexMesh mesh, int faceIndex) {
        double sumX = 0;
        double sumZ = 0;
        HexFace face = mesh.faces().get(faceIndex);
        for (int vertexIndex : face.vertexIndices()) {
            Point2D vertex = mesh.vertices().get(vertexIndex);
            sumX += vertex.x();
            sumZ += vertex.z();
        }
        int cornerCount = face.vertexIndices().size();
        return new Point2D(sumX / cornerCount, sumZ / cornerCount);
    }

    private static double distance(Point2D a, Point2D b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
