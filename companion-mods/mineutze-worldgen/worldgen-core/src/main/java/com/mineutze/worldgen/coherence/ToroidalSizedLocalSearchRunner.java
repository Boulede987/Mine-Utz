package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

/**
 * Real, timed test of {@link ParallelSeedSearch} (many independent {@link ToroidalSizedLocalSearch}
 * seeds at once) against the real catalog: identity, size and thickness (no thin snakes) solved
 * jointly on a fine lattice. Reports, per grid size, whether some seed solved, which one, and how
 * long it took - or, if none did, what the closest state is still wrong with, by kind.
 */
public final class ToroidalSizedLocalSearchRunner {

    private static final double ATOM_WIDTH_BLOCKS = 300;
    private static final int TAIL_REACH_CELLS = 2;
    private static final int FLOWER_FROM_CELLS = 13;
    private static final int[] GRID_SIZES = {40, 48};
    private static final ToroidalSizedLocalSearch.Schedule SCHEDULE = new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 5_000_000);
    private static final int SEED_COUNT = 24;
    private static final long STEPS_PER_SEED = 200_000_000;
    private static final long BASE_SEED = 2026091901L;

    private ToroidalSizedLocalSearchRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        ClusterSizeModel model = new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, TAIL_REACH_CELLS, FLOWER_FROM_CELLS);
        int threadCount = Runtime.getRuntime().availableProcessors();

        for (int size : GRID_SIZES) {
            runOnce(table, model, new ToroidalGrid(size, size), threadCount);
        }
    }

    private static void runOnce(RawRuleTable table, ClusterSizeModel model, ToroidalGrid grid, int threadCount) {
        long startNanos = System.nanoTime();
        ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model, BASE_SEED, SEED_COUNT, STEPS_PER_SEED, SCHEDULE, threadCount);
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        double tileSideBlocks = grid.width() * ATOM_WIDTH_BLOCKS * Math.sqrt(3) / 2;
        String header = grid.width() + "x" + grid.height() + " (" + grid.cellCount() + " cells, tile about " + String.format("%,.0f", tileSideBlocks) + " blocks), "
            + SEED_COUNT + " seeds x " + String.format("%,d", STEPS_PER_SEED) + " steps on " + threadCount + " threads: ";
        if (outcome.solved()) {
            System.out.println(header + "SOLVED and independently verified by seed index " + (outcome.seed() - BASE_SEED) + " in " + String.format("%.1fs wall clock", seconds)
                + " (that seed took " + String.format("%,d", outcome.result().steps()) + " steps)");
            return;
        }
        ToroidalSizedLocalSearch.Breakdown closest = ToroidalSizedLocalSearch.breakdown(table, grid, model, outcome.result().closestAssignment());
        System.out.println(
            header + "no seed solved (" + String.format("%.1fs", seconds) + "). Closest, seed index " + (outcome.seed() - BASE_SEED) + ": cost=" + outcome.result().bestCost()
                + " = illegal edges " + closest.illegalEdges() + ", cluster violation " + closest.clusterViolation() + ", missing biomes " + closest.missingBiomes()
        );
    }
}
