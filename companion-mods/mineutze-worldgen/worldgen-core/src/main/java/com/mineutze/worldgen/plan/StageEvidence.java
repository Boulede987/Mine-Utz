package com.mineutze.worldgen.plan;

import java.util.Objects;
import java.util.OptionalInt;

/** {@code closestFailedCost}: lowest search cost among failures; empty if none failed or failures had no cost (render). */
record StageEvidence(int trials, int successes, OptionalInt closestFailedCost) {

    static final StageEvidence EMPTY = new StageEvidence(0, 0, OptionalInt.empty());

    StageEvidence {
        Objects.requireNonNull(closestFailedCost, "closestFailedCost");
        if (trials < 0 || successes < 0 || successes > trials) {
            throw new IllegalArgumentException("StageEvidence: need 0 <= successes <= trials, got " + successes + " of " + trials);
        }
        if (closestFailedCost.isPresent() && (closestFailedCost.getAsInt() < 0 || successes == trials)) {
            throw new IllegalArgumentException(
                "StageEvidence: closest failed cost needs a failure and must not be negative, got " + closestFailedCost + " with " + successes + " of " + trials
            );
        }
    }

    int failures() {
        return trials - successes;
    }

    StageEvidence withSuccess() {
        return new StageEvidence(trials + 1, successes + 1, closestFailedCost);
    }

    StageEvidence withFailure(OptionalInt failedCost) {
        return new StageEvidence(trials + 1, successes, lowerOf(closestFailedCost, failedCost));
    }

    String describe() {
        return successes + " of " + trials;
    }

    private static OptionalInt lowerOf(OptionalInt first, OptionalInt second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return OptionalInt.of(Math.min(first.getAsInt(), second.getAsInt()));
    }
}
