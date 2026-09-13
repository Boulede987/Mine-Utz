package com.mineutze.worldgen.biomegraph;

/**
 * One biome family in the graph. No node privileged - every biome (even a
 * filler-like one) equally restrictable (see {@link BiomeGraph}'s own doc:
 * "universal filler" concept removed).
 *
 * @param selfAdjacencyLimit how many of this biome's own ≤6 mesh neighbors may also be
 *                           same biome - default 0 (self-adjacency banned, see {@link
 *                           BiomeGraph#isAllowed}). Raising it (e.g. desert=1) lets biome
 *                           span multiple hexes as one felt region instead of stretching
 *                           one hex's geometry. Count enforced by {@link
 *                           com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment},
 *                           not here - this is just the cap it reads.
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

    /** Defaults {@code selfAdjacencyLimit} to 0 — today's exact behavior, self-adjacency banned. */
    public BiomeNode(BiomeId id, BiomeSizeRange sizeRange) {
        this(id, sizeRange, 0);
    }
}
