package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

/** Every patch of {@code biome} must share the border {@code requirement} asks for. */
record RequiredBorderLaw(BiomeId biome, ResolvedBorderRequirement requirement) implements GraphLaw {
    @Override
    public String describe() {
        return biome + " required border: " + requirement;
    }
}
