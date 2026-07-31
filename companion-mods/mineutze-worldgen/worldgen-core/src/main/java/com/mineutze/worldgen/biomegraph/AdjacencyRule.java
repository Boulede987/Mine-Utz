package com.mineutze.worldgen.biomegraph;

/**
 * One adjacency relation between two distinct biomes. Absence of a rule for
 * a pair means forbidden by default (whitelist model — see
 * doc/ARCHITECTURE.md layer 1).
 */
public record AdjacencyRule(
    BiomeId biomeA,
    BiomeId biomeB,
    double weight,
    int blendWidthBlocks
) {

    private static final double DEFAULT_WEIGHT = 1.0;

    public AdjacencyRule {
        if (biomeA.equals(biomeB)) {
            throw new IllegalArgumentException(
                "AdjacencyRule cannot relate a biome to itself: " + biomeA);
        }
        if (weight <= 0.0) {
            throw new IllegalArgumentException(
                "AdjacencyRule weight must be positive, got " + weight + " for " + biomeA + "/" + biomeB);
        }
        if (blendWidthBlocks < 0) {
            throw new IllegalArgumentException(
                "AdjacencyRule blendWidthBlocks must be >= 0, got " + blendWidthBlocks + " for " + biomeA + "/" + biomeB);
        }
    }

    public static AdjacencyRule allow(BiomeId biomeA, BiomeId biomeB, int blendWidthBlocks) {
        return new AdjacencyRule(biomeA, biomeB, DEFAULT_WEIGHT, blendWidthBlocks);
    }

    public static AdjacencyRule allow(BiomeId biomeA, BiomeId biomeB, double weight, int blendWidthBlocks) {
        return new AdjacencyRule(biomeA, biomeB, weight, blendWidthBlocks);
    }

    UnorderedBiomePair pair() {
        return UnorderedBiomePair.of(biomeA, biomeB);
    }
}
