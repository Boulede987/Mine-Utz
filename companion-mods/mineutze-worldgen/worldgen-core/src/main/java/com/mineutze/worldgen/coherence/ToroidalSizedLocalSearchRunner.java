package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.nio.file.Path;

/**
 * Real, timed test of {@link ParallelSeedSearch} (many independent {@link ToroidalSizedLocalSearch}
 * seeds at once) against the real catalog: identity, size and shape solved jointly on a fine
 * lattice. Reports, per grid size and {@link ShapeRule}, whether some seed solved, which one, and
 * how long it took - or, if none did, what the closest state is still wrong with, by kind. A
 * solved pattern is also drawn to a PNG and scored against a strict reference thickness rule
 * ({@link #REFERENCE_THICKNESS}) to show how snake-like the accepted shapes still are.
 */
public final class ToroidalSizedLocalSearchRunner {

    private static final double ATOM_WIDTH_BLOCKS = 300;
    private static final int TAIL_REACH_CELLS = 2;
    private static final int OFF = Integer.MAX_VALUE;
    private static final int[] GRID_SIZES = {40, 48};
    private static final ShapeRule[] SHAPE_RULES = {
        new ShapeRule(1.5, OFF, OFF, TAIL_REACH_CELLS),
        new ShapeRule(2.0, OFF, OFF, TAIL_REACH_CELLS),
    };
    private static final ShapeRule REFERENCE_THICKNESS = new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, 3, 13, TAIL_REACH_CELLS);
    private static final ToroidalSizedLocalSearch.Schedule SCHEDULE = new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 5_000_000);
    private static final int SEED_COUNT = 12;
    private static final long STEPS_PER_SEED = 100_000_000;
    private static final long BASE_SEED = 2026091901L;
    private static final Path IMAGE_DIRECTORY = Path.of("build", "sized-patterns");

    private ToroidalSizedLocalSearchRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        int threadCount = Runtime.getRuntime().availableProcessors();

        for (int size : GRID_SIZES) {
            for (ShapeRule shape : SHAPE_RULES) {
                runOnce(table, new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, shape), new ToroidalGrid(size, size), threadCount);
            }
        }
    }

    private static void runOnce(RawRuleTable table, ClusterSizeModel model, ToroidalGrid grid, int threadCount) {
        long startNanos = System.nanoTime();
        ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model, BASE_SEED, SEED_COUNT, STEPS_PER_SEED, SCHEDULE, threadCount);
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        double tileSideBlocks = grid.width() * ATOM_WIDTH_BLOCKS * Math.sqrt(3) / 2;
        String header = grid.width() + "x" + grid.height() + " (tile about " + String.format("%,.0f", tileSideBlocks) + " blocks), perimeter ratio " + model.shape().perimeterRatio() + ", "
            + SEED_COUNT + " seeds x " + String.format("%,d", STEPS_PER_SEED) + " steps on " + threadCount + " threads: ";
        if (outcome.solved()) {
            System.out.println(header + "SOLVED and independently verified by seed index " + (outcome.seed() - BASE_SEED) + " in " + String.format("%.1fs wall clock", seconds)
                + " (that seed took " + String.format("%,d", outcome.result().steps()) + " steps)");
            reportShapeQuality(table, model, grid, outcome);
            return;
        }
        ToroidalSizedLocalSearch.Breakdown closest = ToroidalSizedLocalSearch.breakdown(table, grid, model, outcome.result().closestAssignment());
        System.out.println(
            header + "no seed solved (" + String.format("%.1fs", seconds) + "). Closest, seed index " + (outcome.seed() - BASE_SEED) + ": cost=" + outcome.result().bestCost()
                + " = illegal edges " + closest.illegalEdges() + ", cluster violation " + closest.clusterViolation() + ", missing biomes " + closest.missingBiomes()
        );
    }

    /** Scores the solved pattern against the strict thickness reference (cells that rule would call thin) and writes its picture. */
    private static void reportShapeQuality(RawRuleTable table, ClusterSizeModel model, ToroidalGrid grid, ParallelSeedSearch.Outcome outcome) {
        ClusterSizeModel reference = new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, REFERENCE_THICKNESS);
        int thinCells = ToroidalSizedLocalSearch.breakdown(table, grid, reference, outcome.result().assignment()).clusterViolation();
        Path image = IMAGE_DIRECTORY.resolve("pattern-" + grid.width() + "x" + grid.height() + "-ratio" + model.shape().perimeterRatio() + ".png");
        ToroidalPatternPngExporter.export(grid, outcome.result().assignment(), table.allBiomeIds(), image);
        System.out.println(
            "    shape check against the strict thickness reference (triangle for 3-12 cells, flower core from 13): " + thinCells + " thin cells of " + grid.cellCount()
                + "; picture: " + image.toAbsolutePath()
        );
    }
}
