package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 * count is written to a CSV so the numbers can be re-derived or re-checked. Several
 * {@link Configuration}s can be compared in one run.
 */
public final class ToroidalSolvabilityRunner {

    /** One thing to measure: a grid size, a shape rule and an annealing schedule. */
    private record Configuration(String label, int gridSize, double perimeterRatio, ToroidalSizedLocalSearch.Schedule schedule) {
    }

    /** What one seed ended with: the steps it needed, or {@link SolveStatistics#UNSOLVED} plus the illegal biome pairs its closest state was stuck on. */
    private record Trial(long steps, Map<String, Integer> stuckPairs) {
    }

    private static final double ATOM_WIDTH_BLOCKS = 300;
    private static final int OFF = Integer.MAX_VALUE;
    private static final Configuration[] CONFIGURATIONS = {
        new Configuration("40x40 ratio1.3 short cycle 1M", 40, 1.3, new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 1_000_000)),
    };
    private static final int TRIAL_COUNT = 48;
    private static final long BUDGET_STEPS = 100_000_000;
    private static final long[] REPORTED_BUDGETS = {50_000_000, 100_000_000};
    private static final double[] TARGET_FAILURE_PROBABILITIES = {1e-6};
    private static final long BASE_SEED = 2026091901L;
    private static final Path OUTPUT_DIRECTORY = Path.of("build", "solvability");

    private ToroidalSolvabilityRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        for (Configuration configuration : CONFIGURATIONS) {
            ToroidalGrid grid = new ToroidalGrid(configuration.gridSize(), configuration.gridSize());
            ClusterSizeModel model = new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, new ShapeRule(configuration.perimeterRatio(), OFF, OFF, 2));
            study(table, grid, model, configuration);
        }
    }

    private static void study(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Configuration configuration) {
        int threadCount = Runtime.getRuntime().availableProcessors();
        long startNanos = System.nanoTime();
        List<Trial> trials = runTrials(table, grid, model, configuration.schedule(), threadCount);
        long[] stepsPerTrial = trials.stream().mapToLong(Trial::steps).toArray();
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        SolveStatistics statistics = new SolveStatistics(stepsPerTrial);
        Path csv = writeCsv(stepsPerTrial, configuration);

        String label = configuration.label() + " " + configuration.schedule() + ": ";
        System.out.println("RESULT " + label + statistics.solvedCount() + " of " + statistics.trialCount() + " single seeds solved within " + String.format("%,d", BUDGET_STEPS)
            + " steps (" + String.format("%.0f", seconds) + "s wall clock on " + threadCount + " threads). Trial data: " + csv.toAbsolutePath());
        if (statistics.solvedCount() > 0) {
            System.out.println("RESULT " + label + "steps among solved: fastest " + String.format("%,d", statistics.stepsAtPercentile(0.0)) + ", median "
                + String.format("%,d", statistics.stepsAtPercentile(0.5)) + ", slowest " + String.format("%,d", statistics.stepsAtPercentile(1.0)));
        }
        for (long budget : REPORTED_BUDGETS) {
            reportBudget(label, statistics, budget);
        }
        reportStuckPairs(label, trials);
    }

    /** For the seeds that did NOT solve, which illegal biome pairs their closest state was stuck on: pairs listed by how many failed seeds had them, then by edge count. */
    private static void reportStuckPairs(String label, List<Trial> trials) {
        Map<String, Integer> seedsAffected = new TreeMap<>();
        Map<String, Integer> edgesTotal = new TreeMap<>();
        int failedSeeds = 0;
        for (Trial trial : trials) {
            if (trial.steps() != SolveStatistics.UNSOLVED) {
                continue;
            }
            failedSeeds++;
            trial.stuckPairs().forEach((pair, edges) -> {
                seedsAffected.merge(pair, 1, Integer::sum);
                edgesTotal.merge(pair, edges, Integer::sum);
            });
        }
        System.out.println("RESULT " + label + failedSeeds + " failed seeds; illegal pairs their closest states were stuck on (seeds affected, edges):");
        seedsAffected.entrySet().stream()
            .sorted((first, second) -> second.getValue() != first.getValue().intValue() ? second.getValue() - first.getValue() : edgesTotal.get(second.getKey()) - edgesTotal.get(first.getKey()))
            .limit(20)
            .forEach(entry -> System.out.println("RESULT   " + entry.getKey() + ": in " + entry.getValue() + " seeds, " + edgesTotal.get(entry.getKey()) + " edges"));
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
    private static List<Trial> runTrials(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, ToroidalSizedLocalSearch.Schedule schedule, int threadCount) {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Trial>> trials = new ArrayList<>();
            for (int index = 0; index < TRIAL_COUNT; index++) {
                long seed = BASE_SEED + index;
                trials.add(pool.submit(() -> {
                    ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model, seed, BUDGET_STEPS, schedule);
                    if (result.assignment() != null) {
                        return new Trial(result.steps(), Map.of());
                    }
                    return new Trial(SolveStatistics.UNSOLVED, ToroidalSizedLocalSearch.illegalPairCounts(table, grid, result.closestAssignment()));
                }));
            }
            List<Trial> finished = new ArrayList<>();
            for (Future<Trial> trial : trials) {
                finished.add(trial.get());
            }
            return finished;
        } catch (ExecutionException failure) {
            throw new IllegalStateException("ToroidalSolvabilityRunner: a trial failed: " + failure.getCause(), failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ToroidalSolvabilityRunner: interrupted", interrupted);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Path writeCsv(long[] stepsPerTrial, Configuration configuration) {
        Path file = OUTPUT_DIRECTORY.resolve("screen-" + configuration.label().replace(' ', '_') + ".csv");
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
