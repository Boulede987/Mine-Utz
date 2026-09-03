package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    /**
     * The real property the adaptive clamp exists to guarantee, checked directly rather
     * than by proxy: under an extreme weight mismatch, EVERY face in the mesh (not just
     * the extreme one - a neighbor pulled hard by it could fold too) must still be a
     * simple polygon (no self-intersection), no matter how large the requested
     * displacement is. This replaces an older, weaker version of this test that only
     * checked DISTANCE against a fixed threshold - a proxy for simplicity, not simplicity
     * itself, since a vertex can stay well within any distance bound and still cross an
     * adjacent edge if enough OTHER corners have also moved.
     */
    @Test
    void extremeWeightMismatchNeverProducesASelfIntersectingFace() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int[] triangle = anyRealTriangle(mesh, adjacency);

        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = uniformAssignments(mesh, adjacency, (int) (2 * SIZE));
        assignments.put(triangle[0], new TriangleIdentitySizeAssignment.Assignment(BIOME, 100_000));
        assignments.put(triangle[1], new TriangleIdentitySizeAssignment.Assignment(BIOME, 1));

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE);

        for (int faceIndex = 0; faceIndex < mesh.faces().size(); faceIndex++) {
            List<Point2D> corners = new ArrayList<>();
            for (int vertexIndex : mesh.faces().get(faceIndex).vertexIndices()) {
                corners.add(displaced.getOrDefault(vertexIndex, mesh.vertices().get(vertexIndex)));
            }
            assertTrue(isPolygonSimple(corners), "face " + faceIndex + " became a self-intersecting polygon under an extreme weight mismatch");
        }
    }

    /**
     * The exact case that used to plateau under the old flat 0.4/0.8 displacement clamp:
     * a face targeting roughly half its 6 uniform neighbors' width (the ratio of the real
     * catalog's single most extreme tier-1 mismatch). Under the flat clamp this stuck at
     * ratio 1.30 regardless of calibration rounds - proven to be a geometry-clamp limit,
     * not an iteration limit, by the fact 15 and 60 rounds gave byte-identical results.
     * The adaptive per-face clamp removes that artificial ceiling: it only limits a face
     * as far as ITS OWN real geometry requires, so this same case now converges close to
     * target instead of plateauing.
     */
    @Test
    void theCatalogsMostExtremeSizeMismatchNoLongerPlateausUnderTheAdaptiveClamp() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int baselineWidth = (int) (2 * SIZE);
        int targetWidth = (int) Math.round(baselineWidth * (300.0 / 650.0)); // same ratio as the catalog's most extreme real case
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(center, new TriangleIdentitySizeAssignment.Assignment(BIOME, targetWidth));
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            assignments.put(neighbor, new TriangleIdentitySizeAssignment.Assignment(BIOME, baselineWidth));
        }

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE);
        double realizedWidth = realizedWidthOf(mesh, displaced, center);

        assertEquals(targetWidth, realizedWidth, targetWidth * 0.10, "expected the adaptive clamp to close the old plateau (was stuck at ratio 1.30) to within 10% of target");
    }

    /**
     * The exact regression this test exists to prevent: an EARLIER version of {@link
     * #calibratedWeights} used a MULTIPLICATIVE update ({@code newWeight = oldWeight *
     * ratio}), which measurably failed - {@link #radicalCenter}'s own formula depends on
     * the DIFFERENCE between two weights, never their ratio, so multiplying every face's
     * weight by a similar factor barely moves the diagram even as the weight value itself
     * collapses toward zero (confirmed via a real round-by-round trace at the time: weight
     * fell from 22500 to 39 over 15 rounds while realized area stayed almost flat). The
     * fix (additive correction) must bring a moderate mismatch essentially exactly onto
     * target, not just closer than the uncalibrated one-shot guess.
     */
    @Test
    void calibrationConvergesCloseToTargetForAModerateMismatch() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int center = aFaceWithSixFullyInteriorNeighbors(mesh, adjacency);

        int baselineWidth = (int) (2 * SIZE);
        int targetWidth = (int) (baselineWidth * 0.7); // a moderate mismatch, well short of the catalog's own most extreme cases
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments = new HashMap<>();
        assignments.put(center, new TriangleIdentitySizeAssignment.Assignment(BIOME, targetWidth));
        for (int neighbor : adjacency.neighborFaceIndices().get(center)) {
            assignments.put(neighbor, new TriangleIdentitySizeAssignment.Assignment(BIOME, baselineWidth));
        }

        Map<Integer, Point2D> displaced = WeightedVertexPlacement.displacedVertexPositions(mesh, adjacency, assignments, SIZE);
        double realizedWidth = realizedWidthOf(mesh, displaced, center);

        assertEquals(targetWidth, realizedWidth, targetWidth * 0.05, "expected calibration to converge within 5% of target for a moderate mismatch");
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

    /**
     * Independent reimplementation of the production simplicity check (same standard
     * orientation-based segment-intersection test), not a call into the private
     * production method - these tests exist to verify the real geometric PROPERTY the
     * production code claims to guarantee, so they must not share its implementation, or
     * a shared bug in both would go undetected.
     */
    @Test
    void isPolygonSimpleReturnsTrueForARegularHexagon() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        List<Point2D> corners = new ArrayList<>();
        for (int vertexIndex : mesh.faces().get(anyRealTriangle(mesh, TriangleAdjacency.of(mesh.faces()))[0]).vertexIndices()) {
            corners.add(mesh.vertices().get(vertexIndex));
        }
        assertTrue(isPolygonSimple(corners), "a real, undisplaced regular hexagon must always be simple");
    }

    @Test
    void isPolygonSimpleReturnsFalseForAHandBuiltBowtieQuadrilateral() {
        List<Point2D> bowtie = List.of(
            new Point2D(0, 0), new Point2D(10, 10), new Point2D(10, 0), new Point2D(0, 10)
        ); // edges (0,0)-(10,10) and (10,0)-(0,10) cross in the middle - a textbook bowtie
        assertTrue(!isPolygonSimple(bowtie), "expected a hand-built bowtie quadrilateral to be detected as non-simple");
    }

    @Test
    void isPolygonSimpleDoesNotFlagASharedCornerBetweenAdjacentEdgesAsACrossing() {
        List<Point2D> irregularButSimple = List.of(
            new Point2D(0, 0), new Point2D(10, -2), new Point2D(20, 0),
            new Point2D(18, 15), new Point2D(10, 40), new Point2D(2, 15)
        ); // one corner (10, 40) pulled far out, but the shape never crosses itself
        assertTrue(isPolygonSimple(irregularButSimple), "a heavily irregular but genuinely non-crossing hexagon must not be flagged as unsimple");
    }

    /** Standalone reimplementation, deliberately not sharing code with the production method under test - see the class doc above the first of these tests. */
    private static boolean isPolygonSimple(List<Point2D> corners) {
        int cornerCount = corners.size();
        for (int i = 0; i < cornerCount; i++) {
            for (int j = i + 2; j < cornerCount; j++) {
                if (i == 0 && j == cornerCount - 1) {
                    continue;
                }
                if (segmentsCross(corners.get(i), corners.get((i + 1) % cornerCount), corners.get(j), corners.get((j + 1) % cornerCount))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean segmentsCross(Point2D a1, Point2D a2, Point2D b1, Point2D b2) {
        double d1 = orientation(b1, b2, a1);
        double d2 = orientation(b1, b2, a2);
        double d3 = orientation(a1, a2, b1);
        double d4 = orientation(a1, a2, b2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double orientation(Point2D a, Point2D b, Point2D c) {
        return (b.x() - a.x()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.x() - a.x());
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

    /** A real interior face all 6 of whose own neighbors are ALSO non-hull-touching - so every one of its 6 vertex-triples resolves to a real position, matching a genuine interior-region face rather than one near the mesh's own outer margin. */
    private static int aFaceWithSixFullyInteriorNeighbors(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency) {
        for (Map.Entry<Integer, Set<Integer>> entry : adjacency.neighborFaceIndices().entrySet()) {
            int faceIndex = entry.getKey();
            if (adjacency.hullTouchingFaceIndices().contains(faceIndex)) {
                continue;
            }
            Set<Integer> neighbors = entry.getValue();
            if (neighbors.size() == 6 && neighbors.stream().noneMatch(n -> adjacency.hullTouchingFaceIndices().contains(n))) {
                return faceIndex;
            }
        }
        throw new IllegalStateException("expected at least one fully-interior 6-neighbor face in a real-sized mesh");
    }

    private static double realizedWidthOf(HexMeshBuilder.HexMesh mesh, Map<Integer, Point2D> displaced, int faceIndex) {
        List<Integer> vertexIndices = mesh.faces().get(faceIndex).vertexIndices();
        int cornerCount = vertexIndices.size();
        double sum = 0;
        for (int i = 0; i < cornerCount; i++) {
            Point2D current = displaced.getOrDefault(vertexIndices.get(i), mesh.vertices().get(vertexIndices.get(i)));
            Point2D next = displaced.getOrDefault(vertexIndices.get((i + 1) % cornerCount), mesh.vertices().get(vertexIndices.get((i + 1) % cornerCount)));
            sum += current.x() * next.z() - next.x() * current.z();
        }
        double area = Math.abs(sum) / 2.0;
        return 2.0 * Math.sqrt(area / (3.0 * Math.sqrt(3.0) / 2.0));
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
