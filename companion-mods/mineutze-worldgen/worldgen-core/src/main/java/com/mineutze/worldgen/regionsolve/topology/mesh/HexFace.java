package com.mineutze.worldgen.regionsolve.topology.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * One hex cell of a {@link HexMeshBuilder} lattice: always exactly 6 corner indices, in
 * angular order, into that builder's shared vertex pool — never its own copy of corner
 * positions, so two faces sharing a corner reference the exact same {@code vertices} list
 * entry (see {@link HexMeshBuilder} for why that makes edge-displacement watertightness
 * free).
 *
 * <p>{@code axialPos} is this face's stable, GLOBAL identity — the same physical face gets
 * the same {@code axialPos} regardless of which bounding box {@link HexMeshBuilder#build}
 * was called with (see {@link HexGrid}'s window-independence doc), unlike a plain
 * build-local array index, which depended on that call's own {@code positions} scan order.
 * This lets a face be looked up in a global decided-face cache across independent solve
 * batches, not only within the one mesh it was built into.
 */
public record HexFace(HexGrid.AxialPos axialPos, List<Integer> vertexIndices) implements MeshFace {

    private static final int CORNER_COUNT = 6;

    public HexFace {
        vertexIndices = List.copyOf(vertexIndices);
        if (vertexIndices.size() != CORNER_COUNT) {
            throw new IllegalArgumentException("a hex face must have exactly " + CORNER_COUNT + " corners, got " + vertexIndices.size());
        }
    }

    @Override
    public List<NodeEdge> edges() {
        List<NodeEdge> edges = new ArrayList<>(CORNER_COUNT);
        for (int cornerIndex = 0; cornerIndex < CORNER_COUNT; cornerIndex++) {
            int from = vertexIndices.get(cornerIndex);
            int to = vertexIndices.get((cornerIndex + 1) % CORNER_COUNT);
            edges.add(NodeEdge.of(from, to));
        }
        return edges;
    }
}
