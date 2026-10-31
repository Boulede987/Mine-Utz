package com.mineutze.worldgen.plan;

sealed interface Obstacle permits LawObstacle, MissingBiome, ShapeLimit {
    String describe();
}
