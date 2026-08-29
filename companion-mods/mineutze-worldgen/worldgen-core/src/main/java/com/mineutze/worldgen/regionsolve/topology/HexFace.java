package com.mineutze.worldgen.regionsolve.topology;

import java.util.ArrayList;
import java.util.List;

/**
 * One hex cell of a {@link HexMeshBuilder} lattice: always exactly 6 corner
 * indices, in angular order, into that builder's shared vertex pool — never
 * its own copy of corner positions, so two faces sharing a corner reference
 * the exact same {@code vertices} list entry (see {@link HexMeshBuilder}'s
 * own doc for why that's what makes edge-displacement watertightness free).
 */
public record HexFace(int seedIndex, List<Integer> vertexIndices) implements MeshFace {

    public HexFace {
        vertexIndices = List.copyOf(vertexIndices);
        if (vertexIndices.size() != 6) {
            throw new IllegalArgumentException("a hex face must have exactly 6 corners, got " + vertexIndices.size());
        }
    }

    @Override
    public List<NodeEdge> edges() {
        List<NodeEdge> edges = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            int from = vertexIndices.get(i);
            int to = vertexIndices.get((i + 1) % 6);
            edges.add(NodeEdge.of(from, to));
        }
        return edges;
    }
}
