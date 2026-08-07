package com.mineutze.worldgen.regionsolve.topology;

/**
 * Adjacency is symmetric (if seed A's Voronoi cell touches seed B's, B
 * touches A), so this key treats (a, b) and (b, a) as the same edge
 * regardless of construction order — mirrors
 * {@link com.mineutze.worldgen.biomegraph.UnorderedBiomePair}'s convention.
 */
public record NodeEdge(int a, int b) {

    public static NodeEdge of(int a, int b) {
        if (a == b) {
            throw new IllegalArgumentException("A node cannot have an edge to itself: " + a);
        }
        return a <= b ? new NodeEdge(a, b) : new NodeEdge(b, a);
    }
}
