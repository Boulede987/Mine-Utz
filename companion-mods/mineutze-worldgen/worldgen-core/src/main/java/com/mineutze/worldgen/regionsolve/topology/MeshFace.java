package com.mineutze.worldgen.regionsolve.topology;

import java.util.List;

/**
 * A mesh face with a fixed cycle of edges, generic enough to cover both
 * {@link IndexedTriangle} (3 edges) and {@link HexFace} (6 edges) —
 * {@link TriangleAdjacency}'s shared-edge grouping algorithm only ever calls
 * {@link #edges()}, never inspects a face's concrete shape.
 */
public interface MeshFace {
    List<NodeEdge> edges();
}
