package com.mineutze.worldgen.coherence;

/**
 * Every number that decides how a world's biome map is made (2026-09-20). The real values are
 * {@link #DEFAULT}, proven on 104 world seeds against the real catalog; tests use small ones so a
 * whole plan can be built in a moment.
 *
 * <p>{@code gridSize} cells on a side (the tile is about {@code gridSize * 0.866 * atomWidthBlocks}
 * blocks across), {@code atomWidthBlocks} the width of one cell (the smallest biome size),
 * {@code perimeterRatio} how ragged a biome's outline may be, {@code warpStrengthsStrongestFirst}
 * the border-bending strengths to try, {@code rasterSamplesPerSide} the resolution of the final
 * map (also the resolution it is verified at), {@code sizeToleranceOfMinimum} how far a region's real
 * area may stray from its biome's size range, as a fraction of the biome's minimum.
 */
record PlanSettings(
    int gridSize, double atomWidthBlocks, double perimeterRatio, double[] warpStrengthsStrongestFirst, int rasterSamplesPerSide, double sizeToleranceOfMinimum,
    long patternStepsPerAttempt, int maxPatternAttempts, ToroidalSizedLocalSearch.Schedule patternSchedule
) {

    static final PlanSettings DEFAULT = new PlanSettings(
        40, 300, 1.3, new double[] {30, 26, 22, 18, 14, 10, 6}, 2_598, 0.10, ToroidalSizedPatternGenerator.DEFAULT_STEPS_PER_SEED, ToroidalSizedPatternGenerator.DEFAULT_MAX_SEEDS,
        ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE
    );
}
