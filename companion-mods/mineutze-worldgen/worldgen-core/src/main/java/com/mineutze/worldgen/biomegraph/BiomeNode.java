package com.mineutze.worldgen.biomegraph;

/**
 * One biome family in the graph. No node is privileged — every biome,
 * including whichever one plays a filler-like role in a given graph, is
 * just as restrictable as any other (see {@link BiomeGraph}'s own doc for
 * why the earlier "universal filler" concept was removed).
 *
 * @param selfAdjacencyLimit how many of this biome's own ≤6 mesh neighbors may ALSO be
 *                           this same biome — default 0 (today's exact behavior: self-
 *                           adjacency banned, see {@link BiomeGraph#isAllowed}'s own doc
 *                           for why). Raising this for a specific biome (e.g. desert=1)
 *                           lets it span multiple ordinary hexes as one felt region
 *                           instead of trying to stretch a single hex's own geometry
 *                           further — the actual COUNT is enforced by {@link
 *                           com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment},
 *                           not here; this is just the per-biome cap it reads.
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
