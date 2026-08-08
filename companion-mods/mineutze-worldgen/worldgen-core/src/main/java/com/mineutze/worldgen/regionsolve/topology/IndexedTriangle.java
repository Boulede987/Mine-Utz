package com.mineutze.worldgen.regionsolve.topology;

import java.util.List;

/**
 * A triangle of the working triangulation, referencing point indices rather
 * than points directly. Public so callers of {@link DelaunayTriangulation#triangulateFaces}
 * can use it as a real mesh face, not just an internal construction detail.
 */
public record IndexedTriangle(int a, int b, int c) {

    List<NodeEdge> edges() {
        return List.of(NodeEdge.of(a, b), NodeEdge.of(b, c), NodeEdge.of(c, a));
    }

    boolean hasVertex(int vertexIndex) {
        return a == vertexIndex || b == vertexIndex || c == vertexIndex;
    }
}
