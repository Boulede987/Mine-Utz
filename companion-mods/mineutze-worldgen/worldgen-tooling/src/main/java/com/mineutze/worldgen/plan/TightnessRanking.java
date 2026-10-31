package com.mineutze.worldgen.plan;

import java.util.List;

/** Counts of laws the failed attempts' closest states still broke: co-occurrence, not cause. */
record TightnessRanking(List<RankedObstacle> entries, int failedAttempts) {

    TightnessRanking {
        entries = List.copyOf(entries);
        if (failedAttempts < 0) {
            throw new IllegalArgumentException("TightnessRanking: failedAttempts must not be negative, got " + failedAttempts);
        }
    }
}
