package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.livepath.SeedMeshCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Real-geometry, EXHAUSTIVE (not sampled) complement to {@link DeadEndFrequencySimulation}'s
 * own STRUCTURE_RING_SWEEP - see this session's own conversation (2026-09-17): the abstract
 * {@code com.mineutze.worldgen.reachability} package's own identity+size models found ZERO
 * dead ends across a real swept density range, while STRUCTURE_RING_SWEEP found real ones
 * (11/12) running the REAL algorithm - strong evidence the real dead-end mechanism lives in
 * the GEOMETRIC/vertex-placement layer those abstract models don't touch, and/or in the
 * exact QUERY ORDER real play creates. This tool tests ORDER directly, exhaustively,
 * through the REAL algorithm: a small (N=7), real, densely-spaced point set (a center plus
 * one hex ring around it, real {@code HEX_SIZE_BLOCKS}-derived spacing, matching
 * STRUCTURE_RING_SWEEP's own innermost-ring density) - EVERY permutation of query order
 * tried, not one random order per trial, each against a fresh {@link SeedMeshCache} (a
 * handful of worldSeeds tried, not exhaustive there too - real geometry + real worldSeed
 * hashing gives an infinite/continuous space for that dimension, see this class's own doc
 * on why N is kept small: exhaustive coverage is real N! cost per worldSeed, not free).
 *
 * <p>Complements, does not replace, {@code DeadEndFrequencySimulation}'s own large-N random
 * sampling: exhaustive but small here, sampled but large there.
 */
public final class RealGeometryExhaustiveOrderSweep {

    private static final Path REPORT_PATH = Path.of("doc", "real-geometry-exhaustive-order-sweep-report.txt");
    private static final double HEX_SIZE_BLOCKS = 325;
    private static final MacroGridConfig GRID_CONFIG = MacroGridConfig.DEFAULT;
    // 2x HEX_SIZE_BLOCKS - real spacing STRUCTURE_RING_SWEEP itself used for "close enough
    // that same-ring points share/border each other's hex clusters" (see that class's own
    // doc), reused here for the same real-density reason.
    private static final double RING_RADIUS_BLOCKS = 2 * HEX_SIZE_BLOCKS;
    private static final int POINT_COUNT = 7; // center + 6-point ring - kept small on purpose, see class doc
    private static final long[] WORLD_SEEDS_TO_TRY = {2026, 20260405, 20260916, 42, 2029};
    private static final int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private RealGeometryExhaustiveOrderSweep() {
    }

    private record Trial(long worldSeed, int[] order) {
    }

    private record TrialOutcome(long worldSeed, int[] order, Optional<String> failureMessage) {
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        List<double[]> points = ringPoints(0, 0, RING_RADIUS_BLOCKS, POINT_COUNT);

        List<int[]> orderings = new ArrayList<>();
        collectPermutations(sequentialIndices(POINT_COUNT), 0, orderings);

        List<Trial> trials = new ArrayList<>();
        for (long worldSeed : WORLD_SEEDS_TO_TRY) {
            for (int[] order : orderings) {
                trials.add(new Trial(worldSeed, order));
            }
        }

        System.out.println("Exhaustively trying every order of " + POINT_COUNT + " real points (" + orderings.size() + " orderings) x "
            + WORLD_SEEDS_TO_TRY.length + " worldSeeds = " + trials.size() + " total trials, using " + THREAD_COUNT + " worker threads...");

        List<TrialOutcome> deadEnds = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        double totalSeconds;
        try {
            ExecutorCompletionService<TrialOutcome> completionService = new ExecutorCompletionService<>(executor);
            for (Trial trial : trials) {
                completionService.submit(() -> runOneOrder(trial, points, graph));
            }
            long startNanos = System.nanoTime();
            for (int completed = 1; completed <= trials.size(); completed++) {
                TrialOutcome outcome;
                try {
                    outcome = completionService.take().get();
                } catch (Exception e) {
                    throw new IllegalStateException("RealGeometryExhaustiveOrderSweep: a trial task itself failed unexpectedly (not a dead end - a real bug)", e);
                }
                outcome.failureMessage().ifPresent(message -> {
                    deadEnds.add(outcome);
                    System.out.println("  DEAD END: worldSeed=" + outcome.worldSeed() + ", order=" + describe(outcome.order()) + ": " + message);
                });
                if (completed % 2000 == 0) {
                    System.out.println("  " + completed + "/" + trials.size() + " (" + deadEnds.size() + " dead ends so far)");
                }
            }
            totalSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        } finally {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
        }

        System.out.println();
        System.out.println(deadEnds.size() + "/" + trials.size() + " orderings produced a dead end (" + totalSeconds + "s total).");

        writeReport(points, trials.size(), deadEnds, totalSeconds);
        System.out.println("Report: " + REPORT_PATH.toAbsolutePath().toUri());
    }

    private static TrialOutcome runOneOrder(Trial trial, List<double[]> points, BiomeGraph graph) {
        SeedMeshCache cache = new SeedMeshCache(graph, GRID_CONFIG, trial.worldSeed());
        try {
            for (int index : trial.order()) {
                double[] point = points.get(index);
                cache.biomeAt(GRID_CONFIG.cellContaining(point[0], point[1]));
            }
            return new TrialOutcome(trial.worldSeed(), trial.order(), Optional.empty());
        } catch (IllegalStateException e) {
            return new TrialOutcome(trial.worldSeed(), trial.order(), Optional.of(String.valueOf(e.getMessage())));
        }
    }

    private static int[] sequentialIndices(int n) {
        int[] indices = new int[n];
        for (int i = 0; i < n; i++) {
            indices[i] = i;
        }
        return indices;
    }

    /** Standard recursive Heap-style permutation enumeration - real, complete coverage of every ordering, not sampled. */
    private static void collectPermutations(int[] indices, int k, List<int[]> out) {
        if (k == indices.length) {
            out.add(indices.clone());
            return;
        }
        for (int i = k; i < indices.length; i++) {
            swap(indices, k, i);
            collectPermutations(indices, k + 1, out);
            swap(indices, k, i);
        }
    }

    private static void swap(int[] array, int i, int j) {
        int temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }

    private static String describe(int[] order) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < order.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(order[i]);
        }
        return sb.append("]").toString();
    }

    private static List<double[]> ringPoints(double centerX, double centerZ, double radius, int totalPointCount) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{centerX, centerZ});
        int ringPointCount = totalPointCount - 1;
        for (int i = 0; i < ringPointCount; i++) {
            double angle = 2 * Math.PI * i / ringPointCount;
            points.add(new double[]{centerX + Math.cos(angle) * radius, centerZ + Math.sin(angle) * radius});
        }
        return points;
    }

    private static void writeReport(List<double[]> points, int totalTrials, List<TrialOutcome> deadEnds, double totalSeconds) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("REAL-GEOMETRY EXHAUSTIVE QUERY-ORDER SWEEP\n");
        report.append("============================================\n\n");
        report.append("Real SeedMeshCache/live algorithm - not the abstract reachability package.\n");
        report.append("Points (").append(points.size()).append("): center + ").append(points.size() - 1).append("-point ring, radius ")
            .append(RING_RADIUS_BLOCKS).append(" blocks\n");
        report.append("WorldSeeds tried: ").append(WORLD_SEEDS_TO_TRY.length).append("\n");
        report.append("Total trials (all orderings x all worldSeeds): ").append(totalTrials).append("\n");
        report.append("Wall time: ").append(String.format("%.1f", totalSeconds)).append("s\n\n");
        report.append(deadEnds.size()).append("/").append(totalTrials).append(" orderings produced a dead end.\n\n");
        for (TrialOutcome outcome : deadEnds) {
            report.append("worldSeed=").append(outcome.worldSeed()).append(", order=").append(describe(outcome.order())).append(": ")
                .append(outcome.failureMessage().orElseThrow()).append("\n");
        }
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report.toString(), StandardCharsets.UTF_8);
    }
}
