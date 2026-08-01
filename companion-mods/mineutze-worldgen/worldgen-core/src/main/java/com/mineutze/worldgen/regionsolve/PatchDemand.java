package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;

/** One patch {@link BudgetPlanner} has committed to fitting somewhere in the region. */
public record PatchDemand(BiomeId biomeId, int minCells, int targetCells) {

    public PatchDemand {
        if (minCells <= 0) {
            throw new IllegalArgumentException("minCells must be positive, got " + minCells);
        }
        if (targetCells < minCells) {
            throw new IllegalArgumentException("targetCells (" + targetCells + ") must be >= minCells (" + minCells + ")");
        }
    }
}
