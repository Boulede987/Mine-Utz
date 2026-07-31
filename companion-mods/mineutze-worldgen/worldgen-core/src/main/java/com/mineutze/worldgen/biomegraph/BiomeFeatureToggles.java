package com.mineutze.worldgen.biomegraph;

/**
 * Per-biome overrides for vanilla worldgen features. See doc/ARCHITECTURE.md
 * layer 4 (Feature Gate).
 */
public record BiomeFeatureToggles(boolean riversEnabled, boolean lakesEnabled) {
}
