package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeFeatureToggles;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshLegalityTest {

    private static final String NAMESPACE = "test";
    private static final BiomeId FOREST = id("forest");
    private static final BiomeId PLAINS = id("plains");
    private static final BiomeId BADLANDS = id("badlands");

    // forest-plains and plains-badlands are legal; forest-badlands has no
    // rule at all, mirroring this codebase's real desert/forest/badlands
    // shape of "needs a bridge biome, direct contact is illegal."
    private static final BiomeGraph GRAPH = new BiomeGraph(
        List.of(node(FOREST), node(PLAINS), node(BADLANDS)),
        List.of(AdjacencyRule.allow(FOREST, PLAINS, 10), AdjacencyRule.allow(PLAINS, BADLANDS, 10))
    );

    @Test
    void anyIdentityIsLegalWhenNothingAdjacentIsAssignedYet() {
        FineMesh mesh = singleTriangle();

        assertTrue(MeshLegality.isLegal(mesh, 0, FOREST, Map.of(), GRAPH));
        assertTrue(MeshLegality.isLegal(mesh, 0, BADLANDS, Map.of(), GRAPH));
    }

    @Test
    void edgeAdjacentIncompatibleIdentityBlocksTheCandidate() {
        FineMesh mesh = twoTrianglesSharingADiagonal();
        int edgeNeighbor = mesh.edgeAdjacentFaces(0).get(0);

        Map<Integer, BiomeId> faceIdentity = Map.of(edgeNeighbor, BADLANDS);

        assertFalse(MeshLegality.isLegal(mesh, 0, FOREST, faceIdentity, GRAPH),
            "face 0 shares an edge with the badlands face, and forest-badlands has no rule");
        assertTrue(MeshLegality.isLegal(mesh, 0, PLAINS, faceIdentity, GRAPH),
            "plains-badlands is legal, so face 0 should still be free to take plains");
    }

    /**
     * The regression this whole class exists for: two faces around a
     * degree-4 vertex fan that touch ONLY at that shared point (never an
     * edge) must still be checked against each other. If MeshLegality only
     * looked at edgeAdjacentFaces, this test would wrongly report the
     * badlands candidate as legal, since its actual edge-neighbors in the
     * fan are two different, unassigned faces.
     */
    @Test
    void diagonalOnlyPartnersAreBlockedEvenThoughTheyNeverShareAnEdge() {
        FineMesh mesh = degreeFourFan();
        List<FacePair> pointOnlyPairs = MeshDiagonalPairs.pointOnlyPairsAt(mesh, 0);
        FacePair diagonalPair = pointOnlyPairs.get(0);

        Map<Integer, BiomeId> faceIdentity = new HashMap<>();
        faceIdentity.put(diagonalPair.a(), FOREST);

        assertFalse(MeshLegality.isLegal(mesh, diagonalPair.b(), BADLANDS, faceIdentity, GRAPH),
            "faces " + diagonalPair.a() + " and " + diagonalPair.b() + " touch only at the fan's center vertex, "
                + "but forest/badlands must still be blocked there");
        assertTrue(MeshLegality.isLegal(mesh, diagonalPair.b(), PLAINS, faceIdentity, GRAPH),
            "plains is compatible with forest, so the same candidate face must still accept it");
    }

    private static FineMesh singleTriangle() {
        return FineMesh.build(uncoloredScatter(new Point2D(0, 0), new Point2D(10, 0), new Point2D(5, 10)));
    }

    private static FineMesh twoTrianglesSharingADiagonal() {
        return FineMesh.build(uncoloredScatter(
            new Point2D(0, 0), new Point2D(10, 0), new Point2D(10, 10), new Point2D(0, 7)
        ));
    }

    private static FineMesh degreeFourFan() {
        return FineMesh.build(uncoloredScatter(
            new Point2D(0, 0), new Point2D(10, 1), new Point2D(1, 9), new Point2D(-11, -2), new Point2D(-2, -8)
        ));
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
        return new BiomeNode(
            id,
            new BiomeSizeRange(100, 200),
            new BiomeShapeHints(0.5, 0.5, 0.5),
            new BiomeFeatureToggles(true, true),
            GrowthCohesionProfile.UNRESTRICTED
        );
    }
}
