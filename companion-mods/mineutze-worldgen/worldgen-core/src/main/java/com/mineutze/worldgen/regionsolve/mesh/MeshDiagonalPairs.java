package com.mineutze.worldgen.regionsolve.mesh;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds faces that touch only at a single shared vertex, never at a shared
 * edge — the mesh analog of the pixel pipeline's diagonal-corner check, but
 * genuinely reachable even on a raw (unmerged) fine triangulation, not just
 * a post-merge artifact: a degree-4 vertex with fan A-B-C-D (consecutive
 * pairs edge-adjacent) has A and C touching only at that point, and nothing
 * about edge-adjacency alone ever inspects that pair. Two faces sharing a
 * vertex are, by definition, either edge-adjacent (their shared vertex sits
 * on a shared edge) or point-only (it doesn't) — no angular/cyclic sorting
 * is needed to tell those apart, just {@link FineMesh#edgeAdjacentFaces}.
 */
public final class MeshDiagonalPairs {

    private MeshDiagonalPairs() {
    }

    /** Every pair of faces incident to {@code vertexId} that don't share an edge with each other. */
    public static List<FacePair> pointOnlyPairsAt(FineMesh mesh, int vertexId) {
        List<Integer> incident = mesh.incidentFaces(vertexId);
        List<FacePair> pairs = new ArrayList<>();
        for (int i = 0; i < incident.size(); i++) {
            for (int j = i + 1; j < incident.size(); j++) {
                int faceA = incident.get(i);
                int faceB = incident.get(j);
                if (!mesh.edgeAdjacentFaces(faceA).contains(faceB)) {
                    pairs.add(FacePair.of(faceA, faceB));
                }
            }
        }
        return pairs;
    }

    /**
     * Every face that shares some vertex with {@code faceId} but isn't
     * edge-adjacent to it — {@code faceId}'s "diagonal partners," across
     * all 3 of its vertices, deduplicated. This is the form
     * {@code MeshLegality} actually needs at merge time: "if I assign this
     * face identity I, which already-assigned faces would I be
     * point-touching, that I'd otherwise never check against?"
     */
    public static Set<Integer> diagonalPartnersOf(FineMesh mesh, int faceId) {
        Set<Integer> partners = new LinkedHashSet<>();
        var face = mesh.face(faceId);
        for (int vertexId : List.of(face.a(), face.b(), face.c())) {
            for (int otherFaceId : mesh.incidentFaces(vertexId)) {
                if (otherFaceId != faceId && !mesh.edgeAdjacentFaces(faceId).contains(otherFaceId)) {
                    partners.add(otherFaceId);
                }
            }
        }
        return partners;
    }
}
