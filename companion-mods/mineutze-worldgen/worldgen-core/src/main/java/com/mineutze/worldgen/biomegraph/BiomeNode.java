package com.mineutze.worldgen.biomegraph;

/**
 * One biome family in the graph. No node is privileged — every biome,
 * including whichever one plays a filler-like role in a given graph, is
 * just as restrictable as any other (see {@link BiomeGraph}'s own doc for
 * why the earlier "universal filler" concept was removed).
 */
public record BiomeNode(
    BiomeId id,
    BiomeSizeRange sizeRange,
    GrowthCohesionProfile cohesionProfile
) {

    public BiomeNode {
        if (id == null) {
            throw new IllegalArgumentException("BiomeNode id must not be null");
        }
        if (sizeRange == null || cohesionProfile == null) {
            throw new IllegalArgumentException("BiomeNode " + id + " is missing a required field");
        }
    }
}
