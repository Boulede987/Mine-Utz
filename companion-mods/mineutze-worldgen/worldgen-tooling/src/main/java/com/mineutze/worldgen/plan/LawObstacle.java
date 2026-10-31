package com.mineutze.worldgen.plan;

record LawObstacle(GraphLaw law) implements Obstacle {
    @Override
    public String describe() {
        return law.describe();
    }
}
