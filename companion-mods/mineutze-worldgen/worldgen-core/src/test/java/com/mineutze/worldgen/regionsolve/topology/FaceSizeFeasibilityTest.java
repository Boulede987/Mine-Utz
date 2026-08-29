package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceSizeFeasibilityTest {

    private static final double SIZE = 200;
    private static final BiomeId BIOME = new BiomeId("test", "a");

    /**
     * The direction this whole class exists to get right, tested without re-deriving
     * FaceSizeFeasibility's own side-determination logic: swapping which of two adjacent
     * faces wants to grow (much bigger target vs. a smaller one) must flip the shared
     * edge's bias sign, since the bias is defined relative to a fixed geometric direction,
     * not to either face's own identity.
     */
    @Test
    void swappingWhichFaceWantsToGrowFlipsTheBiasSign() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int[] pair = findAnyAdjacentNonHullPair(mesh, adjacency);
        int f1 = pair[0];
        int f2 = pair[1];

        int widthSmall = 50;
        int widthLarge = 400;

        Map<NodeEdge, Double> biasWhenF1Grows = FaceSizeFeasibility.biasBlocksByEdge(
            mesh, adjacency, Map.of(f1, new TriangleIdentitySizeAssignment.Assignment(BIOME, widthLarge), f2, new TriangleIdentitySizeAssignment.Assignment(BIOME, widthSmall))
        );
        Map<NodeEdge, Double> biasWhenF2Grows = FaceSizeFeasibility.biasBlocksByEdge(
            mesh, adjacency, Map.of(f1, new TriangleIdentitySizeAssignment.Assignment(BIOME, widthSmall), f2, new TriangleIdentitySizeAssignment.Assignment(BIOME, widthLarge))
        );

        assertEquals(1, biasWhenF1Grows.size());
        assertEquals(1, biasWhenF2Grows.size());
        double f1GrowsBias = biasWhenF1Grows.values().iterator().next();
        double f2GrowsBias = biasWhenF2Grows.values().iterator().next();

        assertTrue(f1GrowsBias != 0.0, "expected a real bias when the two faces' targets differ this much");
        assertTrue(f1GrowsBias * f2GrowsBias < 0, "swapping which face wants to grow should flip the bias sign, got " + f1GrowsBias + " and " + f2GrowsBias);
    }

    @Test
    void equalTargetsProduceNoBias() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int[] pair = findAnyAdjacentNonHullPair(mesh, adjacency);

        Map<NodeEdge, Double> bias = FaceSizeFeasibility.biasBlocksByEdge(
            mesh, adjacency, Map.of(
                pair[0], new TriangleIdentitySizeAssignment.Assignment(BIOME, 300),
                pair[1], new TriangleIdentitySizeAssignment.Assignment(BIOME, 300)
            )
        );
        assertEquals(0.0, bias.values().iterator().next(), 1e-9);
    }

    /** A regular hexagon's side length equals its own circumradius exactly, so every edge in this mesh is exactly {@code SIZE} long — the clamp bound is a known constant, not something that needs measuring per edge. */
    @Test
    void extremeTargetMismatchStaysClampedToASimplePolygon() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());
        int[] pair = findAnyAdjacentNonHullPair(mesh, adjacency);

        Map<NodeEdge, Double> bias = FaceSizeFeasibility.biasBlocksByEdge(
            mesh, adjacency, Map.of(
                pair[0], new TriangleIdentitySizeAssignment.Assignment(BIOME, 100_000),
                pair[1], new TriangleIdentitySizeAssignment.Assignment(BIOME, 1)
            )
        );
        double maxAllowedBias = 0.4 * SIZE;
        assertTrue(Math.abs(bias.values().iterator().next()) <= maxAllowedBias + 1e-9, "bias exceeded the self-intersection-safe clamp");
    }

    @Test
    void hullTouchingNeighborsAreSkippedEntirely() {
        HexMeshBuilder.HexMesh mesh = HexMeshBuilder.build(SIZE, 0, 0, 3000, 3000);
        TriangleAdjacency.Adjacency adjacency = TriangleAdjacency.of(mesh.faces());

        // Assign only ONE hull-touching face - none of its edges have a neighbor also in `assignments`.
        int hullTouchingFace = adjacency.hullTouchingFaceIndices().iterator().next();
        Map<NodeEdge, Double> bias = FaceSizeFeasibility.biasBlocksByEdge(
            mesh, adjacency, Map.of(hullTouchingFace, new TriangleIdentitySizeAssignment.Assignment(BIOME, 300))
        );
        assertTrue(bias.isEmpty(), "a face with no assigned neighbor must contribute no edge bias");
    }

    private static int[] findAnyAdjacentNonHullPair(HexMeshBuilder.HexMesh mesh, TriangleAdjacency.Adjacency adjacency) {
        Set<Integer> hullTouching = adjacency.hullTouchingFaceIndices();
        for (Map.Entry<Integer, Set<Integer>> entry : adjacency.neighborFaceIndices().entrySet()) {
            if (hullTouching.contains(entry.getKey())) {
                continue;
            }
            for (int neighbor : entry.getValue()) {
                if (!hullTouching.contains(neighbor)) {
                    return new int[]{entry.getKey(), neighbor};
                }
            }
        }
        throw new IllegalStateException("expected at least one non-hull-touching adjacent pair in a real-sized mesh");
    }
}
