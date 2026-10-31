package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

record MissingBiome(BiomeId biome) implements Obstacle {
    @Override
    public String describe() {
        return biome + " present in the map";
    }
}
