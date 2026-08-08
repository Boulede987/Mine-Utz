package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeFeatureToggles;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshRasterizerTest {

    private static final String NAMESPACE = "test";

    @Test
    void everyCellInsideASingleTriangleResolvesToItsIdentity() {
        BiomeId solo = id("solo");
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(-5, -5), new Point2D(20, -5), new Point2D(5, 20)
        ));
        MeshAssignment assignment = new MeshAssignment(new LinkedHashMap<>(Map.of(0, solo)), Set.of());
        RegionSolveInput input = inputWith(new MacroGridConfig(64, 6), solo);

        Map<MacroCellPos, BiomeId> result = MeshRasterizer.rasterize(mesh, assignment, Map.of(), input);

        assertEquals(36, result.size());
        assertTrue(result.values().stream().allMatch(solo::equals));
    }

    @Test
    void aRegionEntirelyOutsideTheMeshFallsBackToTheNearestFaceWithoutCrashing() {
        BiomeId solo = id("solo");
        // A tiny triangle far from the region's own macro-cell coordinates.
        FineMesh mesh = FineMesh.build(uncoloredScatter(
            new Point2D(100, 100), new Point2D(110, 100), new Point2D(105, 110)
        ));
        MeshAssignment assignment = new MeshAssignment(new LinkedHashMap<>(Map.of(0, solo)), Set.of());
        RegionSolveInput input = inputWith(new MacroGridConfig(64, 3), solo);

        Map<MacroCellPos, BiomeId> result = MeshRasterizer.rasterize(mesh, assignment, Map.of(), input);

        assertEquals(9, result.size(), "every cell must still resolve, via the fallback path");
        assertTrue(result.values().stream().allMatch(solo::equals), "only one face exists in the mesh at all, so the fallback has exactly one possible answer");
    }

    @Test
    void displacedVertexPositionsChangeTheRasterizedOutput() {
        BiomeId left = id("left");
        BiomeId right = id("right");
        BiomeGraph graph = new BiomeGraph(List.of(node(left), node(right)), List.of(AdjacencyRule.allow(left, right, 10)));
        FineMesh mesh = jitteredScatterMesh(8, 8, 21);
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>();
        for (int faceId = 0; faceId < mesh.faces().size(); faceId++) {
            Point2D centroid = MeshAreas.centroid(mesh, mesh.face(faceId));
            faceIdentity.put(faceId, centroid.x() < 40.0 ? left : right);
        }
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());
        RegionSolveInput input = new RegionSolveInput(graph, new MacroGridConfig(64, 71), new RegionPos(0, 0), Map.of(), 42L);

        Map<MacroCellPos, BiomeId> baseline = MeshRasterizer.rasterize(mesh, assignment, Map.of(), input);

        // Displace every vertex near the split boundary far in the +x direction -
        // a large, deliberately unsubtle shove so at least some boundary cell must
        // flip from "right" to "left" in the rasterized output.
        Map<Integer, Point2D> displaced = new LinkedHashMap<>();
        for (int vertexId = 0; vertexId < mesh.points().size(); vertexId++) {
            Point2D position = mesh.position(vertexId);
            if (Math.abs(position.x() - 40.0) < 6.0) {
                displaced.put(vertexId, new Point2D(position.x() + 20.0, position.z()));
            }
        }

        Map<MacroCellPos, BiomeId> afterDisplacement = MeshRasterizer.rasterize(mesh, assignment, displaced, input);

        assertNotEquals(baseline, afterDisplacement, "shoving the shared boundary 20 units sideways must change at least one cell's rasterized biome");
    }

    private static FineMesh jitteredScatterMesh(int columns, int rows, long seed) {
        Random random = new Random(seed);
        List<ScatteredPoint> points = new java.util.ArrayList<>();
        for (int index = 0; index < columns * rows; index++) {
            int gridX = index % columns;
            int gridZ = index / columns;
            Point2D position = new Point2D(gridX * 10.0 + random.nextDouble() * 4.0, gridZ * 10.0 + random.nextDouble() * 4.0);
            points.add(new ScatteredPoint(position, Optional.empty()));
        }
        return FineMesh.build(points);
    }

    private static RegionSolveInput inputWith(MacroGridConfig grid, BiomeId biome) {
        BiomeGraph graph = new BiomeGraph(List.of(node(biome)), List.of());
        return new RegionSolveInput(graph, grid, new RegionPos(0, 0), Map.of(), 1L);
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
