package com.mineutze.worldgen.biomegraph;

/**
 * One adjacency relation between two distinct biomes. Absence of a rule for
 * a pair means forbidden by default (whitelist model).
 */
public record AdjacencyRule(
    BiomeId biomeA,
    BiomeId biomeB,
    int blendWidthBlocks
) {

    public AdjacencyRule {
        if (biomeA.equals(biomeB)) {
            throw new IllegalArgumentException(
                "AdjacencyRule cannot relate a biome to itself: " + biomeA);
        }
        if (blendWidthBlocks < 0) {
            throw new IllegalArgumentException(
                "AdjacencyRule blendWidthBlocks must be >= 0, got " + blendWidthBlocks + " for " + biomeA + "/" + biomeB);
        }
    }

    public static AdjacencyRule allow(BiomeId biomeA, BiomeId biomeB, int blendWidthBlocks) {
        return new AdjacencyRule(biomeA, biomeB, blendWidthBlocks);
    }

    UnorderedBiomePair pair() {
        return UnorderedBiomePair.of(biomeA, biomeB);
    }
}
