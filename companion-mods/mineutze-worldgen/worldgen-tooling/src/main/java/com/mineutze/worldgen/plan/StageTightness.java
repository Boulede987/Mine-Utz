package com.mineutze.worldgen.plan;

import java.util.Objects;

/** One ranking per stage: coarse and fine use different grids and size models, so their counts are not one population. */
public record StageTightness(TightnessRanking coarse, TightnessRanking refinement) {

    public StageTightness {
        Objects.requireNonNull(coarse, "coarse");
        Objects.requireNonNull(refinement, "refinement");
    }
}
