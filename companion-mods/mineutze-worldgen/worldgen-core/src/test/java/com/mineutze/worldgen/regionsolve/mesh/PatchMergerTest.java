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
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchMergerTest {

    private static final String NAMESPACE = "test";
    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(64, 8);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final long WORLD_SEED = 42L;

    @Test
    void aSingleFreePatchGrowsToAtLeastItsTargetArea() {
        BiomeId solo = id("solo");
        BiomeGraph graph = new BiomeGraph(List.of(node("solo", 100, 200)), List.of());
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(), WORLD_SEED);
        SeedGraph seedGraph = seedGraphOf(SeedGraphNode.free(0, new MacroCellPos(3, 3), Set.of(solo)));
        Map<Integer, BiomeId> assignedIdentities = Map.of(0, solo);
        FineMesh mesh = buildMesh(input, seedGraph, assignedIdentities);

        Map<Integer, BiomeId> faceIdentity = PatchMerger.merge(mesh, input, seedGraph, assignedIdentities).faceIdentity();

        assertFalse(faceIdentity.isEmpty(), "the seed must have claimed at least its own starting face");
        assertTrue(faceIdentity.values().stream().allMatch(solo::equals));
        double targetArea = targetAreaMacroCellUnits(graph.node(solo), SMALL_GRID);
        double claimedArea = faceIdentity.keySet().stream().mapToDouble(faceId -> MeshAreas.area(mesh, mesh.face(faceId))).sum();
        assertTrue(claimedArea >= targetArea,
            "claimed area " + claimedArea + " should have reached the small target " + targetArea + " well within an 8x8-cell region");
    }

    @Test
    void twoCompatiblePatchesGrowingSimultaneouslyNeverProduceAnIllegalTouch() {
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 300), node("b", 100, 300)),
            List.of(AdjacencyRule.allow(biomeA, biomeB, 10))
        );
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(), WORLD_SEED);
        SeedGraph seedGraph = seedGraphOf(
            SeedGraphNode.free(0, new MacroCellPos(1, 1), Set.of(biomeA)),
            SeedGraphNode.free(1, new MacroCellPos(6, 6), Set.of(biomeB))
        );
        Map<Integer, BiomeId> assignedIdentities = Map.of(0, biomeA, 1, biomeB);
        FineMesh mesh = buildMesh(input, seedGraph, assignedIdentities);

        Map<Integer, BiomeId> faceIdentity = PatchMerger.merge(mesh, input, seedGraph, assignedIdentities).faceIdentity();

        assertTrue(faceIdentity.containsValue(biomeA) && faceIdentity.containsValue(biomeB), "both patches must have grown at least their seed face");
        assertNoIllegalTouchAnywhere(mesh, faceIdentity, graph);
    }

    @Test
    void aSeedSpotBlockedByAFixedNeighborIsSkippedEntirely() {
        BiomeId friend = id("friend");
        BiomeId stranger = id("stranger");
        BiomeId bridge = id("bridge");
        // friend-bridge-stranger satisfies BiomeGraph's own eager bridgeability
        // check without ever making friend directly legal next to stranger.
        BiomeGraph graph = new BiomeGraph(
            List.of(node("friend", 100, 300), node("stranger", 100, 300), node("bridge", 100, 300)),
            List.of(AdjacencyRule.allow(friend, bridge, 10), AdjacencyRule.allow(bridge, stranger, 10))
        );
        SeedGraph seedGraph = seedGraphOf(
            SeedGraphNode.fixed(0, new MacroCellPos(1, 0), stranger),
            SeedGraphNode.free(1, new MacroCellPos(0, 0), Set.of(friend))
        );
        Map<Integer, BiomeId> assignedIdentities = Map.of(1, friend);
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(), WORLD_SEED);

        // A single triangle: friend's only incident face is the same face
        // stranger's fixed germ also touches, so seedFixedGerms claims it
        // first and friend has nothing legal left to start from.
        FineMesh mesh = FineMesh.build(List.of(
            new ScatteredPoint(new Point2D(0, 0), Optional.of(friend)),
            new ScatteredPoint(new Point2D(1, 0), Optional.of(stranger)),
            new ScatteredPoint(new Point2D(0.5, 1), Optional.empty())
        ));

        MeshAssignment assignment = PatchMerger.merge(mesh, input, seedGraph, assignedIdentities);

        assertFalse(assignment.faceIdentity().containsValue(friend), "friend's only seed face was already claimed by the fixed stranger germ");
        assertEquals(1, assignment.faceIdentity().size());
        assertTrue(assignment.faceIdentity().containsValue(stranger));
        assertEquals(Set.of(assignment.faceIdentity().keySet().iterator().next()), assignment.fixedFaceIds(),
            "the one claimed face is the fixed stranger germ's, and must be tracked as fixed");
    }

    private static void assertNoIllegalTouchAnywhere(FineMesh mesh, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        for (Map.Entry<Integer, BiomeId> entry : faceIdentity.entrySet()) {
            int faceId = entry.getKey();
            BiomeId biome = entry.getValue();
            for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
                BiomeId neighborBiome = faceIdentity.get(neighborFaceId);
                if (neighborBiome != null) {
                    assertTrue(graph.isAllowed(biome, neighborBiome), biome + " (face " + faceId + ") illegally edge-touches " + neighborBiome + " (face " + neighborFaceId + ")");
                }
            }
            for (int diagonalFaceId : MeshDiagonalPairs.diagonalPartnersOf(mesh, faceId)) {
                BiomeId diagonalBiome = faceIdentity.get(diagonalFaceId);
                if (diagonalBiome != null) {
                    assertTrue(graph.isAllowed(biome, diagonalBiome), biome + " (face " + faceId + ") illegally point-touches " + diagonalBiome + " (face " + diagonalFaceId + ")");
                }
            }
        }
    }

    private static FineMesh buildMesh(RegionSolveInput input, SeedGraph seedGraph, Map<Integer, BiomeId> assignedIdentities) {
        return FineMesh.build(FineMeshPointScatter.scatter(input, seedGraph, assignedIdentities));
    }

    private static double targetAreaMacroCellUnits(BiomeNode biomeNode, MacroGridConfig grid) {
        double sideInCells = (double) biomeNode.sizeRange().maxWidthBlocks() / grid.macroCellSizeBlocks();
        return sideInCells * sideInCells;
    }

    private static SeedGraph seedGraphOf(SeedGraphNode... nodes) {
        return new SeedGraph(List.of(nodes), Set.of(), false);
    }

    private static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    private static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeNode(
            id(path),
            new BiomeSizeRange(minWidthBlocks, maxWidthBlocks),
            new BiomeShapeHints(0.5, 0.5, 0.5),
            new BiomeFeatureToggles(true, true),
            GrowthCohesionProfile.UNRESTRICTED
        );
    }
}
