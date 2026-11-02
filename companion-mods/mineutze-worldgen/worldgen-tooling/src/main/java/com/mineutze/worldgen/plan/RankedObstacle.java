package com.mineutze.worldgen.plan;

import java.util.Objects;

record RankedObstacle(Obstacle obstacle, int attemptsBroken) {

    RankedObstacle {
        Objects.requireNonNull(obstacle, "obstacle");
        if (attemptsBroken < 0) {
            throw new IllegalArgumentException("RankedObstacle: attemptsBroken must not be negative, got " + attemptsBroken + " for " + obstacle.describe());
        }
    }
}
