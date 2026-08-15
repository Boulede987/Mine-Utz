package com.mineutze.worldgen.biomegraph;

/**
 * Per-biome overrides for vanilla worldgen features (Feature Gate layer).
 */
public record BiomeFeatureToggles(boolean riversEnabled, boolean lakesEnabled) {
}
