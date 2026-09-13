package com.mineutze.worldgen.biomegraph;

/** Adjacency symmetric (A next to B = B next to A) - treats (a,b) and (b,a) as same pair regardless of construction order. */
record UnorderedBiomePair(BiomeId first, BiomeId second) {

    static UnorderedBiomePair of(BiomeId a, BiomeId b) {
        return a.compareTo(b) <= 0 ? new UnorderedBiomePair(a, b) : new UnorderedBiomePair(b, a);
    }
}
