package com.mineutze.worldgen.regionsolve.topology;

import java.util.List;

/** A triangle of the working triangulation, referencing point indices rather than points directly. */
record IndexedTriangle(int a, int b, int c) {

    List<NodeEdge> edges() {
        return List.of(NodeEdge.of(a, b), NodeEdge.of(b, c), NodeEdge.of(c, a));
    }

    boolean hasVertex(int vertexIndex) {
        return a == vertexIndex || b == vertexIndex || c == vertexIndex;
    }
}
