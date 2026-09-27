package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;

/**
 * Every number that decides how a world's biome map is made (2026-09-20, {@code fineResolution}
 * 2026-09-27). The real values are {@link #DEFAULT}, {@code gridSize}/{@code atomWidthBlocks} proven
 * on 104 world seeds against the real catalog; tests use small ones so a whole plan can be built in
 * a moment.
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
    long patternStepsPerAttempt, int maxPatternAttempts, ToroidalSizedLocalSearch.Schedule patternSchedule, FineResolution fineResolution
) {

    /** No fine stage: {@code gridSize}/{@code atomWidthBlocks} are solved directly, exactly as before {@code fineResolution} existed. */
    PlanSettings(
        int gridSize, double atomWidthBlocks, double perimeterRatio, double[] warpStrengthsStrongestFirst, int rasterSamplesPerSide, double sizeToleranceOfMinimum,
        long patternStepsPerAttempt, int maxPatternAttempts, ToroidalSizedLocalSearch.Schedule patternSchedule
    ) {
        this(
            gridSize, atomWidthBlocks, perimeterRatio, warpStrengthsStrongestFirst, rasterSamplesPerSide, sizeToleranceOfMinimum, patternStepsPerAttempt, maxPatternAttempts,
            patternSchedule, null
        );
    }

    /**
     * A finer grid solved by coarse-then-refine multigrid (2026-09-27, see {@code
     * MultigridPatternGenerator} and {@code DECISIONS.md}) instead of solving {@code gridSize}/
     * {@code atomWidthBlocks} directly: {@code gridSize}/{@code atomWidthBlocks} here are the coarse
     * stage's own proven settings; this record's own {@code gridSize}/{@code atomWidthBlocks} are the
     * FINE grid its solution is upsampled into and then refined at. {@code shapeFloorRatios} is a
     * per-biome {@code ShapeRule.minPerimeterRatio} override at the fine resolution only (the coarse
     * stage never sees it) - a biome named here must have enough real cell-count freedom at the fine
     * atom width for the ratio to be achievable; not validated at construction time (see
     * {@code DECISIONS.md}'s "known gap" note).
     */
    record FineResolution(
        int gridSize, double atomWidthBlocks, long refinementStepsPerAttempt, int maxRefinementAttempts, ToroidalSizedLocalSearch.Schedule refinementSchedule,
        Map<BiomeId, Double> shapeFloorRatios
    ) {
    }

    static final PlanSettings DEFAULT = new PlanSettings(
        40, 300, 1.3, new double[] {30, 26, 22, 18, 14, 10, 6}, 2_598, 0.10, ToroidalSizedPatternGenerator.DEFAULT_STEPS_PER_SEED, ToroidalSizedPatternGenerator.DEFAULT_MAX_SEEDS,
        ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE,
        new FineResolution(
            120, 100, 20_000_000L, 5, new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 9_000_000), Map.of(new BiomeId("minecraft", "beach"), 1.5)
        )
    );
}
