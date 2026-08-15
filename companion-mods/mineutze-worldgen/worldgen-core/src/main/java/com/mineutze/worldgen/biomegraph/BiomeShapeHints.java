package com.mineutze.worldgen.biomegraph;

/**
 * Terrain-shape parameters the Density Router selects per-column from the
 * resolved biome-id.
 */
public record BiomeShapeHints(double roughness, double depthBias, double flatness) {

    private static final double MIN_HINT_VALUE = 0.0;
    private static final double MAX_HINT_VALUE = 1.0;

    public BiomeShapeHints {
        requireInRange("roughness", roughness);
        requireInRange("depthBias", depthBias);
        requireInRange("flatness", flatness);
    }

    private static void requireInRange(String fieldName, double value) {
        if (value < MIN_HINT_VALUE || value > MAX_HINT_VALUE) {
            throw new IllegalArgumentException(
                fieldName + " must be within [" + MIN_HINT_VALUE + ", " + MAX_HINT_VALUE + "], got " + value);
        }
    }
}
