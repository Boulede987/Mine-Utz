package com.mineutze.worldgen.biomegraph;

/**
 * One biome family in the graph. No node privileged - every biome (even a
 * filler-like one) equally restrictable (see {@link BiomeGraph}'s own doc:
 * "universal filler" concept removed).
 *
 * @param selfAdjacencyLimit whether this biome may be next to itself at all (0 = never,
 *                           anything above = yes). The pattern solver does not count
 *                           neighbors: a same-biome cluster is one biome, size range
 *                           decides how big.
 */
public record BiomeNode(
    BiomeId id,
    BiomeSizeRange sizeRange,
    int selfAdjacencyLimit
) {

    public BiomeNode {
        if (id == null) {
            throw new IllegalArgumentException("BiomeNode id must not be null");
        }
        if (sizeRange == null) {
            throw new IllegalArgumentException("BiomeNode " + id + " is missing a required field");
        }
        if (selfAdjacencyLimit < 0) {
            throw new IllegalArgumentException("BiomeNode " + id + " selfAdjacencyLimit must be >= 0, got " + selfAdjacencyLimit);
        }
    }

    /** Defaults {@code selfAdjacencyLimit} to uncapped — see that field's doc. */
    public BiomeNode(BiomeId id, BiomeSizeRange sizeRange) {
        this(id, sizeRange, Integer.MAX_VALUE);
    }
}
