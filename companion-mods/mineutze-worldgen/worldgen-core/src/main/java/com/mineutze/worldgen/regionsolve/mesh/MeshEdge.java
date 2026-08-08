package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.regionsolve.topology.NodeEdge;

import java.util.List;

/**
 * One canonical shared edge of the fine mesh, keyed by its 2 vertex ids
 * ({@link NodeEdge} already canonicalizes the unordered pair). Exactly 1
 * owning face means a mesh-perimeter edge (region boundary ring, or the
 * convex hull edge of the whole scatter); exactly 2 means an interior edge
 * shared by two faces. Building exactly one object per edge — instead of
 * letting each face compute its own boundary independently — is what lets
 * {@code EdgeDisplacement} move a shared border once and have both
 * incident faces read the same displaced position, guaranteeing no cracks
 * can open between them.
 */
public record MeshEdge(NodeEdge vertices, List<Integer> faceIds) {

    public MeshEdge {
        if (faceIds.isEmpty() || faceIds.size() > 2) {
            throw new IllegalArgumentException("MeshEdge " + vertices + " must be owned by 1 or 2 faces, got " + faceIds.size());
        }
        faceIds = List.copyOf(faceIds);
    }

    public boolean isMeshBoundary() {
        return faceIds.size() == 1;
    }

    public boolean isInterior() {
        return faceIds.size() == 2;
    }
}
