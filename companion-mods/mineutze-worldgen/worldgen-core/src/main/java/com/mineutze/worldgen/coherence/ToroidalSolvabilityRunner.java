package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The evidence for "every world seed solves" (2026-09-19): runs many independent single-seed
 * searches against the real catalog, each verified by {@link ToroidalSizedLocalSearch#verify} on
 * success, and reports {@link SolveStatistics} - the per-seed solve rate at several step budgets,
 * how slow the slowest solves were, a confidence bound on the per-seed failure probability, and
 * how many independent restarts per world seed that bound justifies. Every trial's seed and step
 * count is written to a CSV so the numbers can be re-derived or re-checked.
 */
public final class ToroidalSolvabilityRunner {

    private static final double ATOM_WIDTH_BLOCKS = 300;
    private static final int OFF = Integer.MAX_VALUE;
    private static final int GRID_SIZE = 40;
    private static final double[] PERIMETER_RATIOS = {1.25, 1.3};
    private static final ToroidalSizedLocalSearch.Schedule SCHEDULE = new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 5_000_000);
    private static final int TRIAL_COUNT = 120;
    private static final long BUDGET_STEPS = 400_000_000;
    private static final long[] REPORTED_BUDGETS = {25_000_000, 50_000_000, 100_000_000, 200_000_000, 400_000_000};
    private static final double[] TARGET_FAILURE_PROBABILITIES = {1e-6, 1e-9};
    private static final long BASE_SEED = 2026091901L;
    private static final Path OUTPUT_DIRECTORY = Path.of("build", "solvability");

    private ToroidalSolvabilityRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        ToroidalGrid grid = new ToroidalGrid(GRID_SIZE, GRID_SIZE);
        for (double perimeterRatio : PERIMETER_RATIOS) {
            ClusterSizeModel model = new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, new ShapeRule(perimeterRatio, OFF, OFF, 2));
            study(table, grid, model, perimeterRatio);
        }
    }

    private static void study(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, double perimeterRatio) {
        int threadCount = Runtime.getRuntime().availableProcessors();
        long startNanos = System.nanoTime();
        long[] stepsPerTrial = runTrials(table, grid, model, threadCount);
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        SolveStatistics statistics = new SolveStatistics(stepsPerTrial);
        Path csv = writeCsv(stepsPerTrial, perimeterRatio);

        String label = "ratio " + perimeterRatio + " on " + GRID_SIZE + "x" + GRID_SIZE + ": ";
        System.out.println("RESULT " + label + statistics.solvedCount() + " of " + statistics.trialCount() + " single seeds solved within " + String.format("%,d", BUDGET_STEPS)
            + " steps (" + String.format("%.0f", seconds) + "s wall clock on " + threadCount + " threads). Trial data: " + csv.toAbsolutePath());
        if (statistics.solvedCount() > 0) {
            System.out.println("RESULT " + label + "steps among solved: fastest " + String.format("%,d", statistics.stepsAtPercentile(0.0)) + ", median "
                + String.format("%,d", statistics.stepsAtPercentile(0.5)) + ", p90 " + String.format("%,d", statistics.stepsAtPercentile(0.9)) + ", slowest "
                + String.format("%,d", statistics.stepsAtPercentile(1.0)));
        }
        for (long budget : REPORTED_BUDGETS) {
            reportBudget(label, statistics, budget);
        }
    }

    private static void reportBudget(String label, SolveStatistics statistics, long budget) {
        StringBuilder line = new StringBuilder("RESULT " + label + "budget " + String.format("%,d", budget) + " steps: solved "
            + String.format("%.1f%%", 100 * statistics.solvedFractionWithin(budget)) + ", per-seed failure upper bound "
            + String.format("%.2f%%", 100 * statistics.failureProbabilityUpperBound(budget)));
        for (double target : TARGET_FAILURE_PROBABILITIES) {
            line.append(", restarts for failure < ").append(target).append(": ").append(restartsOrNone(statistics, target, budget));
        }
        System.out.println(line);
    }

    private static String restartsOrNone(SolveStatistics statistics, double target, long budget) {
        try {
            return String.valueOf(statistics.restartsForFailureBelow(target, budget));
        } catch (IllegalStateException noRestartCountHelps) {
            return "none justified";
        }
    }

    /** Runs {@link #TRIAL_COUNT} independent seeds, {@code BASE_SEED + index}; the value is the steps that seed needed, or {@link SolveStatistics#UNSOLVED}. A solved trial has already passed the search's own independent verify. */
    private static long[] runTrials(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, int threadCount) {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Long>> trials = new ArrayList<>();
            for (int index = 0; index < TRIAL_COUNT; index++) {
                long seed = BASE_SEED + index;
                trials.add(pool.submit(() -> {
                    ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model, seed, BUDGET_STEPS, SCHEDULE);
                    return result.assignment() != null ? result.steps() : SolveStatistics.UNSOLVED;
                }));
            }
            long[] stepsPerTrial = new long[TRIAL_COUNT];
            for (int index = 0; index < TRIAL_COUNT; index++) {
                stepsPerTrial[index] = trials.get(index).get();
            }
            return stepsPerTrial;
        } catch (ExecutionException failure) {
            throw new IllegalStateException("ToroidalSolvabilityRunner: a trial failed: " + failure.getCause(), failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ToroidalSolvabilityRunner: interrupted", interrupted);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Path writeCsv(long[] stepsPerTrial, double perimeterRatio) {
        Path file = OUTPUT_DIRECTORY.resolve("solvability-" + GRID_SIZE + "x" + GRID_SIZE + "-ratio" + perimeterRatio + ".csv");
        StringBuilder rows = new StringBuilder("seed,stepsToSolve(-1 = not solved within " + BUDGET_STEPS + ")\n");
        for (int index = 0; index < stepsPerTrial.length; index++) {
            rows.append(BASE_SEED + index).append(',').append(stepsPerTrial[index]).append('\n');
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, rows);
        } catch (IOException failure) {
            throw new UncheckedIOException("ToroidalSolvabilityRunner: could not write " + file, failure);
        }
        return file;
    }
}
