package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

/**
 * Real, timed test of {@link ToroidalSizedLocalSearch} against the real catalog: identity, size
 * and thickness (no thin snakes) solved jointly on a fine lattice. Reports, for each grid size, schedule and seed,
 * whether it solved and - if not - what the closest state is still wrong with, by kind.
 */
public final class ToroidalSizedLocalSearchRunner {

    private static final double ATOM_WIDTH_BLOCKS = 300;
    private static final int TAIL_REACH_CELLS = 2;
    private static final int[] FLOWER_FROM_CELLS = {13};
    private static final int[] GRID_SIZES = {48};
    private static final ToroidalSizedLocalSearch.Schedule[] SCHEDULES = {
        new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 5_000_000),
    };
    private static final long MAX_STEPS = 100_000_000;
    private static final long[] RANDOM_SEEDS = {2026091901L, 2026091902L, 2026091903L, 2026091904L, 2026091905L, 2026091906L};

    private ToroidalSizedLocalSearchRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);

        for (int size : GRID_SIZES) {
            ToroidalGrid grid = new ToroidalGrid(size, size);
            double tileSideBlocks = size * ATOM_WIDTH_BLOCKS * Math.sqrt(3) / 2;
            for (int flowerFromCells : FLOWER_FROM_CELLS) {
                ClusterSizeModel model = new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, TAIL_REACH_CELLS, flowerFromCells);
                for (ToroidalSizedLocalSearch.Schedule schedule : SCHEDULES) {
                    for (long randomSeed : RANDOM_SEEDS) {
                        runOnce(table, model, grid, tileSideBlocks, flowerFromCells, schedule, randomSeed);
                    }
                }
            }
        }
    }

    private static void runOnce(
        RawRuleTable table, ClusterSizeModel model, ToroidalGrid grid, double tileSideBlocks, int flowerFromCells, ToroidalSizedLocalSearch.Schedule schedule, long randomSeed
    ) {
        long startNanos = System.nanoTime();
        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model, randomSeed, MAX_STEPS, schedule);
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        String header = grid.width() + "x" + grid.height() + " (" + grid.cellCount() + " cells, tile about " + String.format("%,.0f", tileSideBlocks) + " blocks), flower from " + flowerFromCells + " cells, seed " + randomSeed + ", " + schedule + ": ";
        String rate = String.format("%.1fs, %,d steps, %,.0f steps/s", seconds, result.steps(), result.steps() / Math.max(seconds, 0.001));
        if (result.assignment() != null) {
            System.out.println(header + "SOLVED and independently verified in " + rate);
            return;
        }
        ToroidalSizedLocalSearch.Breakdown closest = ToroidalSizedLocalSearch.breakdown(table, grid, model, result.closestAssignment());
        System.out.println(
            header + "not solved (" + rate + "). Closest: cost=" + result.bestCost() + " = illegal edges " + closest.illegalEdges()
                + ", cluster violation " + closest.clusterViolation() + ", missing biomes " + closest.missingBiomes()
        );
    }
}
