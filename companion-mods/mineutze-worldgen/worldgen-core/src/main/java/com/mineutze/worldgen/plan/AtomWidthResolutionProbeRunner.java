package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures whether the joint identity+size search still converges reasonably at a much finer grid
 * resolution (2026-09-26): today's PRODUCTION schedule/step-budget, unchanged, against the real
 * catalog, at {@code gridSize=120, atomWidthBlocks=100} instead of the shipped {@code 40/300} - a
 * real 9x increase in cell count that preserves the world's physical tile size exactly
 * ({@code 120 * 0.866 * 100} equals {@code 40 * 0.866 * 300}, about 10,392 blocks across).
 *
 * <p>Deliberately does NOT touch {@link PlanSettings#DEFAULT} or the calibration manifest - this is
 * a bounded probe, not a re-tune. The per-seed step/seed budget is reduced from production's
 * defaults specifically so this probe has a known, safe wall-clock ceiling regardless of whether
 * solving actually converges: failing to solve within it is itself a real, useful measurement.
 */
public final class AtomWidthResolutionProbeRunner {

    private static final int PROBE_GRID_SIZE = 120;
    private static final double PROBE_ATOM_WIDTH_BLOCKS = 100.0;
    private static final long PROBE_STEPS_PER_SEED = 5_000_000L;
    private static final int PROBE_MAX_SEEDS = 20;
    private static final long[] WORLD_SEEDS = {42L, 1094L, 20260920L, -7311L, 424242L};
    private static final double WORLD_CREATION_BUDGET_SECONDS_LOW = 300;
    private static final double WORLD_CREATION_BUDGET_SECONDS_HIGH = 480;

    private record SeedResult(long worldSeed, boolean solved, int patternAttempts, double seconds, String failureMessage) {
    }

    private AtomWidthResolutionProbeRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData catalog = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(catalog);
        ToroidalGrid grid = new ToroidalGrid(PROBE_GRID_SIZE, PROBE_GRID_SIZE);
        ClusterSizeModel model = new ClusterSizeModel(
            table, PROBE_ATOM_WIDTH_BLOCKS, new ShapeRule(PlanSettings.DEFAULT.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2)
        );
        int threads = Runtime.getRuntime().availableProcessors();

        System.out.println(
            "PROBE grid " + PROBE_GRID_SIZE + "x" + PROBE_GRID_SIZE + " at atom width " + PROBE_ATOM_WIDTH_BLOCKS + " blocks (" + grid.cellCount()
                + " cells, " + (grid.cellCount() / (double) (40 * 40)) + "x production's 1600) - schedule and perimeter ratio UNCHANGED from PlanSettings.DEFAULT, budget reduced to "
                + PROBE_STEPS_PER_SEED + " steps x " + PROBE_MAX_SEEDS + " seeds per world seed, " + threads + " threads"
        );

        List<SeedResult> results = new ArrayList<>();
        for (long worldSeed : WORLD_SEEDS) {
            runSeed(table, grid, model, worldSeed, threads, results);
        }
        reportResults(results);
    }

    private static void runSeed(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long worldSeed, int threads, List<SeedResult> results) {
        long start = System.nanoTime();
        try {
            ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(
                table, grid, model, worldSeed, PROBE_STEPS_PER_SEED, PROBE_MAX_SEEDS, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE, threads
            );
            double seconds = secondsSince(start);
            System.out.println("SEED " + worldSeed + ": solved at attempt " + (generated.seedIndex() + 1) + " in " + String.format(Locale.ROOT, "%.1f", seconds) + "s");
            results.add(new SeedResult(worldSeed, true, generated.seedIndex() + 1, seconds, null));
        } catch (IllegalStateException failure) {
            double seconds = secondsSince(start);
            System.out.println("SEED " + worldSeed + ": did NOT solve within budget, " + String.format(Locale.ROOT, "%.1f", seconds) + "s spent - " + failure.getMessage());
            results.add(new SeedResult(worldSeed, false, -1, seconds, failure.getMessage()));
        }
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static void reportResults(List<SeedResult> results) {
        long solvedCount = results.stream().filter(SeedResult::solved).count();
        System.out.println();
        System.out.println("RESULT " + solvedCount + " of " + results.size() + " world seeds solved within the reduced probe budget");
        if (solvedCount == 0) {
            System.out.println("RESULT no timing data - none solved; re-run with a larger step/seed budget before drawing conclusions");
            return;
        }
        double[] solvedSeconds = results.stream().filter(SeedResult::solved).mapToDouble(SeedResult::seconds).sorted().toArray();
        double min = solvedSeconds[0];
        double max = solvedSeconds[solvedSeconds.length - 1];
        double median = solvedSeconds[solvedSeconds.length / 2];
        System.out.println(
            "RESULT solve time among solved seeds: min " + String.format(Locale.ROOT, "%.1f", min) + "s, median " + String.format(Locale.ROOT, "%.1f", median) + "s, max "
                + String.format(Locale.ROOT, "%.1f", max) + "s"
        );
        System.out.println("RESULT verdict versus the 5-8 minute world-creation budget: " + verdict(max));
    }

    private static String verdict(double maxSolvedSeconds) {
        if (maxSolvedSeconds <= WORLD_CREATION_BUDGET_SECONDS_LOW) {
            return "within budget even at the low end (" + String.format(Locale.ROOT, "%.1f", maxSolvedSeconds) + "s <= 300s) - and this used only " + PROBE_STEPS_PER_SEED
                + " steps x " + PROBE_MAX_SEEDS + " seeds, an eighth/tenth of production's real budget, so there is real headroom left unused.";
        }
        if (maxSolvedSeconds <= WORLD_CREATION_BUDGET_SECONDS_HIGH) {
            return "within the high end of budget (" + String.format(Locale.ROOT, "%.1f", maxSolvedSeconds) + "s <= 480s) but not the low end - worth re-tuning before committing.";
        }
        return "OVER budget (" + String.format(Locale.ROOT, "%.1f", maxSolvedSeconds) + "s > 480s) even at a reduced step/seed budget - re-tuning the schedule is required, not optional, before this resolution is viable.";
    }
}
