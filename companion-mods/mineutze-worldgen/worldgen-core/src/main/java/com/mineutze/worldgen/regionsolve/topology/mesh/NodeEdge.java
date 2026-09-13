package com.mineutze.worldgen.regionsolve.topology.mesh;

/**
 * Adjacency is symmetric (if seed A's Voronoi cell touches seed B's, B
 * touches A), so this key treats (first, second) and (second, first) as the
 * same edge regardless of construction order — mirrors
 * {@link com.mineutze.worldgen.biomegraph.UnorderedBiomePair}'s convention
 * (same field names, same canonical-ordering idiom).
 */
public record NodeEdge(int first, int second) {

    public static NodeEdge of(int first, int second) {
        if (first == second) {
            throw new IllegalArgumentException("A node cannot have an edge to itself: " + first);
        }
        return first <= second ? new NodeEdge(first, second) : new NodeEdge(second, first);
    }
}
