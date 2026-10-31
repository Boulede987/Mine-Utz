package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

/** Each patch of {@code biome} holds {@code minCells..maxCells} coarse cells - from its min/max width. */
record SizeRangeLaw(BiomeId biome, int minCells, int maxCells) implements GraphLaw {
    @Override
    public String describe() {
        return biome + " size range (" + minCells + ".." + maxCells + " cells per patch)";
    }
}
