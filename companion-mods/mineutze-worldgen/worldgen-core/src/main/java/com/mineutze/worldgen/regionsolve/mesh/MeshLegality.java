package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;

/**
 * The mesh-native analog of {@code GrowthEngine.isLegal}, extended with the
 * diagonal-touch gate the pixel pipeline never had a structural handle for
 * (see {@link MeshDiagonalPairs}). A candidate face may take an identity
 * only if the {@link BiomeGraph} allows it next to every already-assigned
 * face it either shares an edge with OR touches only at a shared vertex —
 * checking both here, at merge time, is deliberately preventive rather than
 * a post-hoc cleanup pass: a diagonal-only illegal touch can exist on the
 * raw fine triangulation before any merging even happens (see
 * {@link MeshDiagonalPairs}'s own doc for the worked counterexample), so
 * catching it after the fact would be too late for a face that's already
 * been committed.
 *
 * <p>Fixed/germ points ({@code FineMeshPointScatter}) are unified into the
 * same face-identity map as ordinary growth claims, so this one rule covers
 * boundary legality too — no separate special case needed for "touches a
 * fixed neighbor."
 */
public final class MeshLegality {

    private MeshLegality() {
    }

    public static boolean isLegal(FineMesh mesh, int faceId, BiomeId candidate, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        for (int neighborFaceId : mesh.edgeAdjacentFaces(faceId)) {
            if (!allowedAgainst(candidate, neighborFaceId, faceIdentity, graph)) {
                return false;
            }
        }
        for (int diagonalFaceId : MeshDiagonalPairs.diagonalPartnersOf(mesh, faceId)) {
            if (!allowedAgainst(candidate, diagonalFaceId, faceIdentity, graph)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allowedAgainst(BiomeId candidate, int otherFaceId, Map<Integer, BiomeId> faceIdentity, BiomeGraph graph) {
        BiomeId otherBiome = faceIdentity.get(otherFaceId);
        return otherBiome == null || graph.isAllowed(candidate, otherBiome);
    }
}
