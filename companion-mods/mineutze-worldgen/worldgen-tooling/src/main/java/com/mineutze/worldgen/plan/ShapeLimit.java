package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

/** Shape rules live in PlanSettings, not the graph: named apart from GraphLaw so nobody looks for them in the editor. */
record ShapeLimit(BiomeId biome) implements Obstacle {
    @Override
    public String describe() {
        return biome + " shape limit (perimeter ceiling or floor, or thickness)";
    }
}
