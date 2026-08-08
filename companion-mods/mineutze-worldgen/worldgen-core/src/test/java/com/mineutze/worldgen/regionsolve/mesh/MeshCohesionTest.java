package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshCohesionTest {

    private static final BiomeId PLAINS = new BiomeId("test", "plains");
    private static final int PAST_BOOTSTRAP = 3; // >= MeshCohesion's own bootstrap threshold

    /**
     * 2 faces sharing exactly 1 edge is the minimal fixture that already
     * proves the neck-prevention mechanism: a candidate here can never have
     * more than 1 same-identity edge-neighbor, structurally, no matter what
     * — the same ceiling a real 1-wide triangle strip's second face hits
     * (its only already-claimed neighbor is the one it grew from; the third
     * one hasn't been reached yet). Reusing this already-verified fixture is
     * more robust than hand-guessing a multi-face strip's exact Delaunay
     * shape.
     */
    private static FineMesh twoFaceMesh() {
        return FineMesh.build(List.of(
            new ScatteredPoint(new Point2D(0, 0), Optional.empty()),
            new ScatteredPoint(new Point2D(10, 0), Optional.empty()),
            new ScatteredPoint(new Point2D(10, 10), Optional.empty()),
            new ScatteredPoint(new Point2D(0, 7), Optional.empty())
        ));
    }

    @Test
    void permissiveProfileAcceptsASingleSameIdentityEdgeNeighbor() {
        // twoFaceMesh has exactly faces 0 and 1, edge-adjacent to each other (verified by FineMeshTest).
        FineMesh mesh = twoFaceMesh();
        Map<Integer, BiomeId> faceIdentity = Map.of(1, PLAINS);

        assertTrue(MeshCohesion.satisfies(mesh, 0, PLAINS, faceIdentity, GrowthCohesionProfile.UNRESTRICTED, PAST_BOOTSTRAP));
    }

    @Test
    void chunkyProfileRejectsASingleSameIdentityEdgeNeighbor() {
        FineMesh mesh = twoFaceMesh();
        Map<Integer, BiomeId> faceIdentity = Map.of(1, PLAINS);
        GrowthCohesionProfile chunky = new GrowthCohesionProfile(2, 0, 0);

        assertFalse(MeshCohesion.satisfies(mesh, 0, PLAINS, faceIdentity, chunky, PAST_BOOTSTRAP),
            "a candidate with only 1 possible same-identity edge-neighbor can never satisfy the 2-of-3 neck check "
                + "- exactly the ceiling a real 1-wide triangle strip's second face hits");
    }

    @Test
    void directionalProfileForcesThePermissiveThresholdEvenWithAHighMinSameBiomeNeighbors() {
        FineMesh mesh = twoFaceMesh();
        Map<Integer, BiomeId> faceIdentity = Map.of(1, PLAINS);
        GrowthCohesionProfile elongated = new GrowthCohesionProfile(4, 4, 2);

        assertTrue(MeshCohesion.satisfies(mesh, 0, PLAINS, faceIdentity, elongated, PAST_BOOTSTRAP),
            "a biome that opted into directional elongation is explicitly allowed to stay thin");
    }

    @Test
    void bootstrapExemptionSkipsTheCheckEntirelyBelowThreeClaimedFaces() {
        FineMesh mesh = twoFaceMesh();
        GrowthCohesionProfile chunky = new GrowthCohesionProfile(2, 0, 0);

        assertTrue(MeshCohesion.satisfies(mesh, 0, PLAINS, Map.of(), chunky, 0),
            "a brand-new germ with zero same-identity neighbors anywhere must still be allowed to grow its first faces");
        assertTrue(MeshCohesion.satisfies(mesh, 0, PLAINS, Map.of(), chunky, 2));
    }
}
