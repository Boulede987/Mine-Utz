package com.mineutze.worldgen.plan;

import java.time.Duration;
import java.util.Objects;

/** Editor default = user decision, see DECISIONS.md 2026-10-31. */
public record FeasibilityBudget(Duration wallTime, int threads, double maxWorldFailureProbability, double confidence) {

    private static final Duration EDITOR_WALL_TIME = Duration.ofMinutes(2);
    private static final double EDITOR_MAX_WORLD_FAILURE_PROBABILITY = 0.01;
    private static final double EDITOR_CONFIDENCE = 0.99;

    public static final FeasibilityBudget EDITOR_DEFAULT = new FeasibilityBudget(
        EDITOR_WALL_TIME, Runtime.getRuntime().availableProcessors(), EDITOR_MAX_WORLD_FAILURE_PROBABILITY, EDITOR_CONFIDENCE
    );

    public FeasibilityBudget {
        Objects.requireNonNull(wallTime, "FeasibilityBudget: wallTime");
        if (wallTime.isNegative() || wallTime.isZero()) {
            throw new IllegalArgumentException("FeasibilityBudget: wallTime must be positive, got " + wallTime);
        }
        if (threads <= 0) {
            throw new IllegalArgumentException("FeasibilityBudget: threads must be positive, got " + threads);
        }
        requireOpenUnitInterval("maxWorldFailureProbability", maxWorldFailureProbability);
        requireOpenUnitInterval("confidence", confidence);
    }

    private static void requireOpenUnitInterval(String name, double value) {
        if (!(value > 0.0 && value < 1.0)) {
            throw new IllegalArgumentException("FeasibilityBudget: " + name + " must be strictly between 0 and 1, got " + value);
        }
    }
}
