package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;

import java.util.Map;

/**
 * Mesh-native neck prevention — the analog of {@code GrowthEngine.satisfiesCohesion}'s
 * Moore-neighbor count, but a triangle only ever has 3 edge-neighbors, so
 * "2+" doesn't map continuously from the pixel grid's 0-8 scale the way it
 * did there. Requiring 2 of a candidate's (up to 3) edge-neighbors to
 * already belong to the same patch is enough on its own: in a sequential
 * frontier expansion, a 1-wide chain of triangles T1(germ)-T2-T3-T4...
 * where each interior Ti has exactly 2 in-chain neighbors, absorbing T3
 * needs 2 already-claimed neighbors — but at claim time only T2 is claimed
 * (T4 doesn't exist yet), so T3 has exactly 1 same-identity edge-neighbor
 * and fails. A 1-wide triangle strip structurally cannot grow past its
 * second face under this rule.
 *
 * <p><b>Counting diagonal partners too (tried, reverted) nearly disabled
 * neck-prevention entirely</b>: a real mesh's average vertex degree is ~6
 * (Euler's formula), so diagonal partners proliferate almost everywhere —
 * confirmed directly with a deliberately-engineered "dumbbell" test
 * fixture (two dense clusters joined by a visually thin bridge) where even
 * a COMPACT profile flooded 100% of the mesh once diagonal partners
 * counted toward the threshold too. The real fix for {@code MeshGapFiller}
 * starving sparse-germ biomes (see that class's own doc) lives there, not
 * here — this class's edge-only neck-prevention stays exactly as
 * originally designed and proven.
 *
 * <p>{@link GrowthCohesionProfile#directionalRunMajor()} has no mesh
 * equivalent (no x/z axis on a triangle) but its INTENT does: a biome that
 * opted into a directional-run profile is explicitly saying "I'm allowed to
 * be thin and elongated" — exactly what a forced separator needs, and
 * exactly what this whole mesh rewrite is trying to make look organic
 * instead of blocked. So a directional profile forces the permissive
 * threshold regardless of {@code minSameBiomeNeighbors}, rather than being
 * dropped outright.
 */
public final class MeshCohesion {

    /** A germ's first faces can't possibly have 2 same-identity edge-neighbors yet — same reasoning as GrowthEngine's own seed-ring bootstrap exemption. */
    private static final int BOOTSTRAP_FACE_COUNT = 3;
    private static final int NECK_PREVENTION_THRESHOLD = 2;
    private static final int NO_NECK_CONSTRAINT_THRESHOLD = 1;
    private static final int PIXEL_MIN_SAME_BIOME_NEIGHBORS_THAT_TRIGGERS_NECK_CHECK = 2;

    private MeshCohesion() {
    }

    public static boolean satisfies(
        FineMesh mesh, int candidateFaceId, BiomeId biome, Map<Integer, BiomeId> faceIdentity, GrowthCohesionProfile cohesion, int currentPatchFaceCount
    ) {
        if (currentPatchFaceCount < BOOTSTRAP_FACE_COUNT) {
            return true;
        }
        long sameIdentityEdgeNeighbors = mesh.edgeAdjacentFaces(candidateFaceId).stream()
            .filter(neighborFaceId -> biome.equals(faceIdentity.get(neighborFaceId)))
            .count();
        return sameIdentityEdgeNeighbors >= meshThreshold(cohesion);
    }

    private static int meshThreshold(GrowthCohesionProfile cohesion) {
        if (cohesion.directionalRunMajor() > 0) {
            return NO_NECK_CONSTRAINT_THRESHOLD;
        }
        return cohesion.minSameBiomeNeighbors() >= PIXEL_MIN_SAME_BIOME_NEIGHBORS_THAT_TRIGGERS_NECK_CHECK
            ? NECK_PREVENTION_THRESHOLD
            : NO_NECK_CONSTRAINT_THRESHOLD;
    }
}
