package com.mineutze.worldgen.plan;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Single caller thread. */
final class TightnessTally {

    private final Map<Obstacle, Integer> attemptsBrokenByObstacle = new HashMap<>();
    private int failedAttempts;

    void add(Set<Obstacle> obstaclesOfOneAttempt) {
        failedAttempts++;
        obstaclesOfOneAttempt.forEach(obstacle -> attemptsBrokenByObstacle.merge(obstacle, 1, Integer::sum));
    }

    /** Ties by type then description, so output never depends on hash order. */
    TightnessRanking ranking() {
        Comparator<RankedObstacle> mostBrokenThenByTypeAndDescription = Comparator.comparingInt(RankedObstacle::attemptsBroken).reversed()
            .thenComparing(ranked -> ranked.obstacle().getClass().getSimpleName())
            .thenComparing(ranked -> ranked.obstacle().describe());
        return new TightnessRanking(
            attemptsBrokenByObstacle.entrySet().stream().map(entry -> new RankedObstacle(entry.getKey(), entry.getValue())).sorted(mostBrokenThenByTypeAndDescription).toList(),
            failedAttempts
        );
    }
}
