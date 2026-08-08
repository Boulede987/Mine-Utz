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
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshPatchSizeEnforcerTest {

    private static final String NAMESPACE = "test";
    private static final MacroGridConfig GRID = new MacroGridConfig(64, 8);

    @Test
    void anUndersizedComponentGetsAbsorbedIntoItsOnlyLegalNeighbor() {
        BiomeId small = id("small");
        BiomeId big = id("big");
        // The full quad this fixture splits into 2 triangles has area 85 - small's
        // declared min (5000 blocks -> ~6104 macro-cell^2 area) safely exceeds that
        // no matter which of the 2 triangles ends up bigger.
        BiomeGraph graph = new BiomeGraph(
            List.of(node(small, 5000, 9000), node(big, 100, 900)),
            List.of(AdjacencyRule.allow(small, big, 10))
        );
        FineMesh mesh = twoTrianglesSharingADiagonal();
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>(Map.of(0, small, 1, big));
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());

        MeshPatchSizeEnforcer.enforceMinimumPatchSize(mesh, assignment, graph, GRID);

        assertEquals(big, assignment.faceIdentity().get(0), "the undersized 'small' component must have been absorbed into its only legal neighbor");
        assertEquals(big, assignment.faceIdentity().get(1));
    }

    @Test
    void aComponentAlreadyAtOrAboveItsMinimumIsLeftAlone() {
        BiomeId small = id("small");
        BiomeId big = id("big");
        // min=1 block is trivially satisfied by any real triangle area - never undersized.
        BiomeGraph graph = new BiomeGraph(
            List.of(node(small, 1, 900), node(big, 100, 900)),
            List.of(AdjacencyRule.allow(small, big, 10))
        );
        FineMesh mesh = twoTrianglesSharingADiagonal();
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>(Map.of(0, small, 1, big));
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());

        MeshPatchSizeEnforcer.enforceMinimumPatchSize(mesh, assignment, graph, GRID);

        assertEquals(small, assignment.faceIdentity().get(0));
        assertEquals(big, assignment.faceIdentity().get(1));
    }

    /**
     * A single-neighbor "no legal replacement" fixture doesn't actually
     * prove anything: replacing an undersized component with its ONE
     * bordering neighbor's own identity is always trivially legal against
     * that same neighbor (same-biome-touching-itself). A genuine
     * no-legal-replacement case needs the component bordered by TWO
     * mutually-incompatible identities at once — replacing with either one
     * still illegally touches the other. Uses the degree-4 fan fixture
     * (also used by {@code MeshDiagonalPairsTest}/{@code MeshLegalityTest}):
     * finds a diagonal (point-only) pair X/Y, then a face Z edge-adjacent
     * to both — Z is the "small" component, X and Y (mutually
     * incompatible) are its two real neighbors.
     */
    @Test
    void anUndersizedComponentBorderedByTwoMutuallyIncompatibleNeighborsIsLeftAloneRatherThanForced() {
        BiomeId small = id("small");
        BiomeId x = id("x");
        BiomeId y = id("y");
        BiomeId bridge = id("bridge");
        // x-y have no rule between them - both bridge through "bridge". small-bridge is
        // an otherwise-unused rule, needed only to satisfy BiomeGraph's own eager
        // bridgeability check (every node pair must connect within 3 hops) - it plays
        // no role in the actual property this test checks (whether x or y is a legal
        // whole-component REPLACEMENT for small, which never depends on small's own
        // adjacency rules at all).
        BiomeGraph graph = new BiomeGraph(
            List.of(node(small, 5000, 9000), node(x, 100, 900), node(y, 100, 900), node(bridge, 100, 900)),
            List.of(AdjacencyRule.allow(x, bridge, 10), AdjacencyRule.allow(bridge, y, 10), AdjacencyRule.allow(small, bridge, 10))
        );

        FineMesh mesh = degreeFourFan();
        List<FacePair> diagonalPairs = MeshDiagonalPairs.pointOnlyPairsAt(mesh, 0);
        FacePair diagonalPair = diagonalPairs.get(0);
        int connector = mesh.edgeAdjacentFaces(diagonalPair.a()).stream()
            .filter(candidate -> mesh.edgeAdjacentFaces(diagonalPair.b()).contains(candidate))
            .findFirst().orElseThrow(() -> new AssertionError("expected a connector face between the diagonal pair in a 4-fan"));

        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>(Map.of(diagonalPair.a(), x, diagonalPair.b(), y, connector, small));
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());

        MeshPatchSizeEnforcer.enforceMinimumPatchSize(mesh, assignment, graph, GRID);

        assertEquals(small, assignment.faceIdentity().get(connector),
            "bordered by two mutually-incompatible identities - neither is a legal whole-component replacement, so it must be left as-is");
    }

    private static FineMesh degreeFourFan() {
        return FineMesh.build(List.of(
            new ScatteredPoint(new Point2D(0, 0), Optional.empty()),
            new ScatteredPoint(new Point2D(10, 1), Optional.empty()),
            new ScatteredPoint(new Point2D(1, 9), Optional.empty()),
            new ScatteredPoint(new Point2D(-11, -2), Optional.empty()),
            new ScatteredPoint(new Point2D(-2, -8), Optional.empty())
        ));
    }

    @Test
    void neverAbsorbsOrReassignsAFixedFace() {
        BiomeId small = id("small");
        BiomeId big = id("big");
        BiomeGraph graph = new BiomeGraph(
            List.of(node(small, 5000, 9000), node(big, 100, 900)),
            List.of(AdjacencyRule.allow(small, big, 10))
        );
        FineMesh mesh = twoTrianglesSharingADiagonal();
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>(Map.of(0, small, 1, big));
        // Face 0 is fixed context (already-solved neighbor territory) - must survive untouched
        // even though it's "undersized" by this graph's own numbers.
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of(0));

        MeshPatchSizeEnforcer.enforceMinimumPatchSize(mesh, assignment, graph, GRID);

        assertEquals(small, assignment.faceIdentity().get(0), "a fixed face must never be reassigned, undersized or not");
    }

    @Test
    void neverIntroducesAnIllegalTouchOnARealisticMesh() {
        BiomeId a = id("a");
        BiomeId b = id("b");
        BiomeGraph graph = new BiomeGraph(
            List.of(node(a, 400, 900), node(b, 100, 900)),
            List.of(AdjacencyRule.allow(a, b, 10))
        );
        FineMesh mesh = jitteredScatterMesh(8, 8, 11);
        // Alternate small clusters of A among a sea of B, so several undersized A
        // components exist for the enforcer to actually do work on.
        Map<Integer, BiomeId> faceIdentity = new LinkedHashMap<>();
        for (int faceId = 0; faceId < mesh.faces().size(); faceId++) {
            faceIdentity.put(faceId, faceId % 7 == 0 ? a : b);
        }
        MeshAssignment assignment = new MeshAssignment(faceIdentity, Set.of());

        MeshPatchSizeEnforcer.enforceMinimumPatchSize(mesh, assignment, graph, GRID);

        for (Map.Entry<Integer, BiomeId> entry : assignment.faceIdentity().entrySet()) {
            int faceId = entry.getKey();
            BiomeId biome = entry.getValue();
            for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
                BiomeId neighborBiome = assignment.faceIdentity().get(neighborFaceId);
                assertTrue(neighborBiome == null || graph.isAllowed(biome, neighborBiome),
                    biome + " (face " + faceId + ") illegally touches " + neighborBiome + " (face " + neighborFaceId + ")");
            }
        }
    }

    private static FineMesh twoTrianglesSharingADiagonal() {
        return FineMesh.build(List.of(
            new ScatteredPoint(new Point2D(0, 0), Optional.empty()),
            new ScatteredPoint(new Point2D(10, 0), Optional.empty()),
            new ScatteredPoint(new Point2D(10, 10), Optional.empty()),
            new ScatteredPoint(new Point2D(0, 7), Optional.empty())
        ));
    }

    private static FineMesh jitteredScatterMesh(int columns, int rows, long seed) {
        java.util.Random random = new java.util.Random(seed);
        List<ScatteredPoint> points = new java.util.ArrayList<>();
        for (int index = 0; index < columns * rows; index++) {
            int gridX = index % columns;
            int gridZ = index / columns;
            Point2D position = new Point2D(gridX * 10.0 + random.nextDouble() * 4.0, gridZ * 10.0 + random.nextDouble() * 4.0);
            points.add(new ScatteredPoint(position, Optional.empty()));
        }
        return FineMesh.build(points);
    }

    private static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    private static BiomeNode node(BiomeId id, int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeNode(id, new BiomeSizeRange(minWidthBlocks, maxWidthBlocks), new BiomeShapeHints(0.5, 0.5, 0.5), new BiomeFeatureToggles(true, true), GrowthCohesionProfile.UNRESTRICTED);
    }
}
