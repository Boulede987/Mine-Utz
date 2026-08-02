package com.mineutze.worldgen.biomegraph;

/**
 * One biome family in the graph. {@code universalFiller} marks the node that
 * every other node must be allowed to sit next to (see
 * {@link BiomeGraph}'s validation) — it's the backtracking solver's
 * guaranteed-legal fallback, so backtracking always terminates without
 * relaxing an adjacency constraint.
 */
public record BiomeNode(
    BiomeId id,
    BiomeSizeRange sizeRange,
    BiomeShapeHints shapeHints,
    BiomeFeatureToggles featureToggles,
    boolean universalFiller,
    GrowthCohesionProfile cohesionProfile
) {

    public BiomeNode {
        if (id == null) {
            throw new IllegalArgumentException("BiomeNode id must not be null");
        }
        if (sizeRange == null || shapeHints == null || featureToggles == null || cohesionProfile == null) {
            throw new IllegalArgumentException("BiomeNode " + id + " is missing a required field");
        }
    }
}
