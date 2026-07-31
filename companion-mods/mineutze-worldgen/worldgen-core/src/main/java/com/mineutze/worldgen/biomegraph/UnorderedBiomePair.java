package com.mineutze.worldgen.biomegraph;

/**
 * Adjacency is symmetric (if A can sit next to B, B can sit next to A), so
 * this key treats (a, b) and (b, a) as the same pair regardless of
 * construction order.
 */
record UnorderedBiomePair(BiomeId first, BiomeId second) {

    static UnorderedBiomePair of(BiomeId a, BiomeId b) {
        return a.compareTo(b) <= 0 ? new UnorderedBiomePair(a, b) : new UnorderedBiomePair(b, a);
    }
}
