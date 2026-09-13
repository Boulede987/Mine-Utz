package com.mineutze.worldgen.regionsolve.topology.mesh;

import java.util.List;

/**
 * A mesh face with a fixed cycle of edges, generic enough to cover both a real triangle
 * (3 edges, {@code IndexedTriangle} — test-only, moved to src/test since production only
 * uses {@link HexFace}) and {@link HexFace} (6 edges) — {@link FaceAdjacency}'s shared-edge
 * grouping algorithm only calls {@link #edges()}, never inspects a face's concrete shape.
 */
public interface MeshFace {
    List<NodeEdge> edges();
}
