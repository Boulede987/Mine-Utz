package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A Set: a law broken by several patches of one attempt still counts once for that attempt. */
final class ObstacleFinder {

    private ObstacleFinder() {
    }

    static Set<Obstacle> of(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Map<ToroidalPos, BiomeId> closestState) {
        Set<Obstacle> obstacles = new HashSet<>();
        for (ToroidalSizedLocalSearch.ClusterFacts cluster : ToroidalSizedLocalSearch.clusterFacts(table, grid, model, closestState)) {
            addObstaclesOf(cluster, table, model, obstacles);
        }
        addMissingBiomes(table, closestState, obstacles);
        return obstacles;
    }

    private static void addObstaclesOf(ToroidalSizedLocalSearch.ClusterFacts cluster, RawRuleTable table, ClusterSizeModel model, Set<Obstacle> obstacles) {
        addIllegalPairs(cluster, table, obstacles);
        addSizeRange(cluster, model, obstacles);
        addRequiredBorder(cluster, table, obstacles);
        addShapeLimit(cluster, model, obstacles);
    }

    private static void addIllegalPairs(ToroidalSizedLocalSearch.ClusterFacts cluster, RawRuleTable table, Set<Obstacle> obstacles) {
        BiomeId biome = cluster.biome();
        cluster.edgesToBiome().keySet().stream().filter(other -> !table.isLegal(biome, other)).forEach(other -> obstacles.add(new LawObstacle(new IllegalPairLaw(biome, other))));
    }

    private static void addSizeRange(ToroidalSizedLocalSearch.ClusterFacts cluster, ClusterSizeModel model, Set<Obstacle> obstacles) {
        int minCells = model.minCells(cluster.biome());
        int maxCells = model.maxCells(cluster.biome());
        if (cluster.cellCount() < minCells || cluster.cellCount() > maxCells) {
            obstacles.add(new LawObstacle(new SizeRangeLaw(cluster.biome(), minCells, maxCells)));
        }
    }

    private static void addRequiredBorder(ToroidalSizedLocalSearch.ClusterFacts cluster, RawRuleTable table, Set<Obstacle> obstacles) {
        if (cluster.borderShortfall() == 0) {
            return;
        }
        ResolvedBorderRequirement requirement = table.requirementOf(cluster.biome()).orElseThrow(() -> new IllegalStateException(
            "ObstacleFinder: " + cluster.biome() + " is " + cluster.borderShortfall() + " edges short of a required border it does not have"
        ));
        obstacles.add(new LawObstacle(new RequiredBorderLaw(cluster.biome(), requirement)));
    }

    /** Mirrors ClusterSizeModel.violation's shape terms: thin cells, perimeter over the ceiling, perimeter under the floor. */
    private static void addShapeLimit(ToroidalSizedLocalSearch.ClusterFacts cluster, ClusterSizeModel model, Set<Obstacle> obstacles) {
        ShapeRule shape = model.shape(cluster.biome());
        int cells = cluster.cellCount();
        boolean broken = cluster.thinCells() > 0 || cluster.perimeter() > shape.allowedPerimeter(cells) || cluster.perimeter() < shape.requiredMinimumPerimeter(cells);
        if (broken) {
            obstacles.add(new ShapeLimit(cluster.biome()));
        }
    }

    private static void addMissingBiomes(RawRuleTable table, Map<ToroidalPos, BiomeId> closestState, Set<Obstacle> obstacles) {
        Set<BiomeId> present = new HashSet<>(closestState.values());
        table.allBiomeIds().stream().filter(biome -> !present.contains(biome)).forEach(biome -> obstacles.add(new MissingBiome(biome)));
    }
}
