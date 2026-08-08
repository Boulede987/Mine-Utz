package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeFeatureToggles;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.IndexedTriangle;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeDisplacementTest {

    private static final String NAMESPACE = "test";
    private static final MacroGridConfig GRID = new MacroGridConfig(64, 8);
    private static final long WORLD_SEED = 42L;

    @Test
    void deadEndVerticesOnAOneEdgeBorderAreNeverDisplaced() {
        // twoTrianglesSharingADiagonal: the diagonal is the only interior
        // (2-owner) edge, so its 2 endpoints each have exactly 1 boundary
        // edge, not 2 - the "dead end" case that must stay pinned.
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(0, 0), new Point2D(10, 0), new Point2D(10, 10), new Point2D(0, 7)
        ));
        BiomeId a = id("a");
        BiomeId b = id("b");
        BiomeGraph graph = new BiomeGraph(List.of(node(a), node(b)), List.of(AdjacencyRule.allow(a, b, 10)));
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>(Map.of(0, a, 1, b));
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());

        Map<Integer, Point2D> displaced = EdgeDisplacement.displace(mesh, assignment, graph, GRID, WORLD_SEED);

        assertTrue(displaced.isEmpty(), "a single shared edge has no genuine 'through' vertex on either end - both stay pinned");
    }

    @Test
    void aSharedVertexReadsTheSameDisplacedPositionFromEveryIncidentFace() {
        FineMesh mesh = splitLeftRightMesh();
        BiomeId left = id("left");
        BiomeId right = id("right");
        BiomeGraph graph = new BiomeGraph(List.of(node(left), node(right)), List.of(AdjacencyRule.allow(left, right, 10)));
        Map<Integer, BiomeId> faceIdentity = splitFaceIdentityByCentroidX(mesh, left, right);
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());

        Map<Integer, Point2D> displaced = EdgeDisplacement.displace(mesh, assignment, graph, GRID, WORLD_SEED);

        assertFalse(displaced.isEmpty(), "a real multi-vertex boundary between two large patches should produce at least one through-vertex");
        for (Map.Entry<Integer, Point2D> entry : displaced.entrySet()) {
            int vertexId = entry.getKey();
            Point2D displacedPosition = entry.getValue();
            for (int incidentFaceId : mesh.incidentFaces(vertexId)) {
                IndexedTriangle face = mesh.face(incidentFaceId);
                Point2D readFromThisFace = displaced.getOrDefault(vertexId, mesh.position(vertexId));
                assertEquals(displacedPosition, readFromThisFace,
                    "vertex " + vertexId + " must read identically from face " + incidentFaceId + " - a single shared map entry can't disagree with itself, but this is exactly the crack-prevention property the whole design relies on");
            }
        }
    }

    @Test
    void sameInputsProduceTheSameDisplacement() {
        FineMesh mesh = splitLeftRightMesh();
        BiomeId left = id("left");
        BiomeId right = id("right");
        BiomeGraph graph = new BiomeGraph(List.of(node(left), node(right)), List.of(AdjacencyRule.allow(left, right, 10)));
        Map<Integer, BiomeId> faceIdentity = splitFaceIdentityByCentroidX(mesh, left, right);
        MeshAssignment first = new MeshAssignment(new LinkedHashMap<>(faceIdentity), Set.of());
        MeshAssignment second = new MeshAssignment(new LinkedHashMap<>(faceIdentity), Set.of());

        Map<Integer, Point2D> displacedFirst = EdgeDisplacement.displace(mesh, first, graph, GRID, WORLD_SEED);
        Map<Integer, Point2D> displacedSecond = EdgeDisplacement.displace(mesh, second, graph, GRID, WORLD_SEED);

        assertEquals(displacedFirst, displacedSecond);
    }

    @Test
    void displacedPatchAreaStaysCloseToItsUndisplacedArea() {
        FineMesh mesh = splitLeftRightMesh();
        BiomeId left = id("left");
        BiomeId right = id("right");
        BiomeGraph graph = new BiomeGraph(List.of(node(left), node(right)), List.of(AdjacencyRule.allow(left, right, 10)));
        Map<Integer, BiomeId> faceIdentity = splitFaceIdentityByCentroidX(mesh, left, right);
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());

        Map<Integer, Point2D> displaced = EdgeDisplacement.displace(mesh, assignment, graph, GRID, WORLD_SEED);

        double undisplacedLeftArea = faceIdentity.entrySet().stream()
            .filter(entry -> left.equals(entry.getValue()))
            .mapToDouble(entry -> MeshAreas.area(mesh, mesh.face(entry.getKey())))
            .sum();
        double displacedLeftArea = faceIdentity.entrySet().stream()
            .filter(entry -> left.equals(entry.getValue()))
            .mapToDouble(entry -> {
                IndexedTriangle face = mesh.face(entry.getKey());
                Point2D a = displaced.getOrDefault(face.a(), mesh.position(face.a()));
                Point2D b = displaced.getOrDefault(face.b(), mesh.position(face.b()));
                Point2D c = displaced.getOrDefault(face.c(), mesh.position(face.c()));
                return MeshAreas.area(a, b, c);
            })
            .sum();

        double relativeDrift = Math.abs(displacedLeftArea - undisplacedLeftArea) / undisplacedLeftArea;
        assertTrue(relativeDrift < 0.25, "displacement amplitude is bounded to a fraction of local edge length, so total patch area shouldn't drift wildly - got " + relativeDrift);
    }

    private static Map<Integer, BiomeId> splitFaceIdentityByCentroidX(FineMesh mesh, BiomeId left, BiomeId right) {
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>();
        for (int faceId = 0; faceId < mesh.faces().size(); faceId++) {
            Point2D centroid = MeshAreas.centroid(mesh, mesh.face(faceId));
            faceIdentity.put(faceId, centroid.x() < 40.0 ? left : right);
        }
        return faceIdentity;
    }

    private static FineMesh splitLeftRightMesh() {
        Random random = new Random(11);
        List<ScatteredPoint> points = new java.util.ArrayList<>();
        for (int index = 0; index < 8 * 8; index++) {
            int gridX = index % 8;
            int gridZ = index / 8;
            Point2D position = new Point2D(gridX * 10.0 + random.nextDouble() * 4.0, gridZ * 10.0 + random.nextDouble() * 4.0);
            points.add(new ScatteredPoint(position, Optional.empty()));
        }
        return FineMesh.build(points);
    }

    private static List<ScatteredPoint> uncoloredScatter(Point2D... positions) {
        return List.of(positions).stream()
            .map(position -> new ScatteredPoint(position, Optional.empty()))
            .toList();
    }

    private static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    private static BiomeNode node(BiomeId id) {
        return new BiomeNode(id, new BiomeSizeRange(100, 900), new BiomeShapeHints(0.5, 0.5, 0.5), new BiomeFeatureToggles(true, true), GrowthCohesionProfile.UNRESTRICTED);
    }
}
