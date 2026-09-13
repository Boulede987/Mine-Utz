package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.regionsolve.topology.mesh.HexGrid;

import com.mineutze.worldgen.regionsolve.livepath.SeedMeshCache;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.topology.Point2D;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Empirical measurement of real dead-end frequency the tentative/permanent redesign
 * leaves behind (see redesign's own plan doc + chat investigation: mathematically-derived
 * 100% guarantee provably impossible for themed 48-biome catalog - {@code BiomeGraph
 * .requireEveryLocalNeighborhoodSolvable} already proves 23.6% of realizable 6-rings have
 * no universal candidate - honest question is HOW OFTEN a real query pattern hits one, not
 * whether it can happen in principle).
 *
 * <p><b>Two scenarios, different adversarial levels:</b>
 * <ul>
 *   <li>{@code RING_PROBE} - worst case redesign's two-attempt recovery CANNOT protect
 *   against: all 6 real neighbors of center face queried DIRECTLY (each individually
 *   promoted PERMANENT, a true hard wall) before center itself touched - exact
 *   structure-placement ring-search pattern that caused ORIGINAL worldSeed=42 crash
 *   earlier this session. Measures graph's own raw 23.6% bad-ring rate as it manifests
 *   through real biome placement (not a uniform combinatorial sample over abstract rule
 *   graph).</li>
 *   <li>{@code ORGANIC_WALK} - random walk of small steps, matching ordinary exploration.
 *   Two-attempt recovery DOES help here (most nearby context stays tentative, freely
 *   revisable), so should show much lower rate - practical benefit for real gameplay.
 *   First run measured 1/10,000 (0.01%), confirmed genuine CSP dead end
 *   (worldSeed=291692674858800685) via this class's own exception-message capture, not
 *   the unrelated extreme-coordinate artifact found earlier - see git history / plan doc
 *   for full story.</li>
 * </ul>
 *
 * <p>Every trial uses FRESH {@link SeedMeshCache} (no cross-trial reuse) so each is an
 * independent sample - not thousands of small solves against one shared cache (would
 * mostly measure cache-hit behavior, not fresh dead-end risk). Each trial fully
 * independent of every other (own private in-memory stores, nothing shared) -
 * embarrassingly parallel, run across a thread pool instead of sequentially.
 *
 * <p><b>Parallel + reproducible, not one or the other:</b> single shared {@code Random}
 * consumed sequentially across worker threads would race or make exact trial set depend
 * on nondeterministic thread scheduling - bad for reproducing a failure later. Instead,
 * one {@link SplittableRandom} per SCENARIO is {@code split()} on MAIN thread, once per
 * trial, fixed sequential order, before that trial's split-off instance goes to a worker
 * thread - standard deterministic parallel decomposition pattern. Trial #4,872 always
 * gets same (worldSeed, center) regardless of thread pool scheduling.
 */
public final class DeadEndFrequencySimulation {

    // Matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually,
    // same established convention as ModBiomeGraphTest's own solveAndRasterizeOverworld.
    private static final double HEX_SIZE_BLOCKS = 325;
    private static final MacroGridConfig GRID_CONFIG = MacroGridConfig.DEFAULT;

    // Pointy-top axial neighbor deltas, real ring order (matches HexGrid's own pointy-top
    // convention - see centerOf doc).
    private static final int[][] AXIAL_NEIGHBOR_DELTAS = {
        {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}
    };

    private static final int TRIALS_PER_SCENARIO = 100_000;
    // Spread centers across huge area so no two trials' 2500-block cluster caps overlap /
    // reuse each other's work - each trial must be genuinely independent. Comfortably within
    // vanilla's default ~30,000,000-block world border (real gameplay never approaches this).
    // Smaller diagnostic run at 500,000 confirmed a SEPARATE crash ("not covered by any
    // nearby face") at 50,000,000 was a low-priority floating-point artifact from unrealistic
    // extreme coordinates, not a fundamental rasterizer/closure bug - zero repro at this spread.
    private static final double CENTER_SPREAD_BLOCKS = 2_000_000;
    private static final int ORGANIC_WALK_STEPS = 20;
    private static final double ORGANIC_WALK_STEP_BLOCKS = 300;

    // Leaves one logical core free for OS/rest of machine - long batch job, not
    // latency-sensitive, so most/all cores fine, but starving the machine isn't necessary.
    // Measured on build machine: 6 physical/12 logical cores - real speedup well under full
    // logical-core count (GC/memory bandwidth shared under allocation pressure, hyperthreading
    // gives <2x per physical core for branchy allocation-heavy work like CSP backtracking) -
    // still worth parallelizing, just don't over-promise the exact factor.
    private static final int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private DeadEndFrequencySimulation() {
    }

    public static void main(String[] args) throws InterruptedException {
        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        System.out.println("Using " + THREAD_COUNT + " worker threads (" + Runtime.getRuntime().availableProcessors() + " logical cores detected).");
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            System.out.println("Running " + TRIALS_PER_SCENARIO + " RING_PROBE trials (worst case: all 6 real neighbors queried directly before the center)...");
            Result ringProbe = runTrials(executor, graph, new SplittableRandom(2026), TRIALS_PER_SCENARIO, DeadEndFrequencySimulation::runRingProbeTrial);
            System.out.println(ringProbe);

            System.out.println("Running " + TRIALS_PER_SCENARIO + " ORGANIC_WALK trials (random small-step exploration)...");
            Result organicWalk = runTrials(executor, graph, new SplittableRandom(20260405), TRIALS_PER_SCENARIO, DeadEndFrequencySimulation::runOrganicWalkTrial);
            System.out.println(organicWalk);

            System.out.println();
            System.out.println("RING_PROBE:   " + ringProbe.deadEnds().size() + "/" + ringProbe.trials() + " dead ends (" + ringProbe.ratePercent() + "%)");
            System.out.println("ORGANIC_WALK: " + organicWalk.deadEnds().size() + "/" + organicWalk.trials() + " dead ends (" + organicWalk.ratePercent() + "%)");

            printFailureDetails("RING_PROBE", ringProbe);
            printFailureDetails("ORGANIC_WALK", organicWalk);
        } finally {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
        }
    }

    private static void printFailureDetails(String label, Result result) {
        if (result.deadEnds().isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(label + "'s own " + result.deadEnds().size() + " failure(s), full detail:");
        for (Failure failure : result.deadEnds()) {
            System.out.println("  worldSeed=" + failure.worldSeed() + ", center=(" + failure.centerX() + "," + failure.centerZ() + "): " + failure.message());
        }
    }

    /** One captured failure - {@code worldSeed}/center reproduce it exactly later; {@code message} is real exception text, not just a count. */
    private record Failure(long worldSeed, double centerX, double centerZ, String message) {
    }

    /** One trial's full outcome, computed on worker thread - collected on main thread, no further sync needed (see class doc: only RNG source needed care). */
    private record TrialOutcome(long worldSeed, double centerX, double centerZ, Optional<String> failureMessage) {
    }

    private record Result(int trials, List<Failure> deadEnds, long totalNanos) {
        double ratePercent() {
            return trials == 0 ? 0.0 : 100.0 * deadEnds.size() / trials;
        }

        @Override
        public String toString() {
            return deadEnds.size() + "/" + trials + " dead ends (" + String.format("%.4f", ratePercent()) + "%), "
                + String.format("%.1f", totalNanos / 1_000_000_000.0) + "s total, "
                + String.format("%.2f", totalNanos / 1_000_000.0 / Math.max(1, trials)) + "ms/trial avg (wall-clock, parallel)";
        }
    }

    @FunctionalInterface
    private interface Trial {
        /** Empty if trial had no dead end; REAL exception message otherwise (never just a boolean - lost real diagnostic value the first time this ran). */
        Optional<String> tryRun(BiomeGraph graph, long worldSeed, double centerX, double centerZ);
    }

    /**
     * Submits all {@code trialCount} trials up front (each own {@code
     * SplittableRandom.split()}-derived deterministic parameters - see class doc), collects
     * results in COMPLETION order via {@link ExecutorCompletionService} (not submission
     * order) so progress reporting reflects live progress under parallelism, not lagging
     * behind whichever task submitted first but runs slowest. Aggregation ({@code deadEnds}
     * list) only ever runs on MAIN thread (single sequential {@code take()} loop below) - no
     * concurrent collection needed despite WORK being fully parallel.
     */
    private static Result runTrials(ExecutorService executor, BiomeGraph graph, SplittableRandom scenarioRandom, int trialCount, Trial trial) {
        ExecutorCompletionService<TrialOutcome> completionService = new ExecutorCompletionService<>(executor);
        for (int i = 0; i < trialCount; i++) {
            SplittableRandom trialRandom = scenarioRandom.split();
            completionService.submit(() -> runOneTrial(graph, trialRandom, trial));
        }

        List<Failure> deadEnds = new ArrayList<>();
        long startNanos = System.nanoTime();
        for (int completed = 1; completed <= trialCount; completed++) {
            TrialOutcome outcome;
            try {
                outcome = completionService.take().get();
            } catch (Exception e) {
                throw new IllegalStateException("DeadEndFrequencySimulation: a trial task itself failed unexpectedly (not a dead end - a real bug in the simulation harness)", e);
            }
            outcome.failureMessage().ifPresent(message -> {
                Failure failure = new Failure(outcome.worldSeed(), outcome.centerX(), outcome.centerZ(), message);
                deadEnds.add(failure);
                System.out.println("  DEAD END: worldSeed=" + failure.worldSeed() + ", center=(" + failure.centerX() + "," + failure.centerZ() + "): " + failure.message());
            });
            if (completed % 1000 == 0) {
                System.out.println("  " + completed + "/" + trialCount + " (" + deadEnds.size() + " dead ends so far)");
            }
        }
        return new Result(trialCount, deadEnds, System.nanoTime() - startNanos);
    }

    private static TrialOutcome runOneTrial(BiomeGraph graph, SplittableRandom trialRandom, Trial trial) {
        long worldSeed = trialRandom.nextLong();
        double centerX = (trialRandom.nextDouble() * 2 - 1) * CENTER_SPREAD_BLOCKS;
        double centerZ = (trialRandom.nextDouble() * 2 - 1) * CENTER_SPREAD_BLOCKS;
        Optional<String> failureMessage = trial.tryRun(graph, worldSeed, centerX, centerZ);
        return new TrialOutcome(worldSeed, centerX, centerZ, failureMessage);
    }

    /**
     * Queries all 6 real neighbors of center's own nearest face DIRECTLY (each becomes
     * permanent, a true hard wall), then queries center itself - exact "structure-search
     * ring" pattern two-attempt recovery can't protect against, since every neighbor is
     * genuinely permanent by the time center is touched.
     */
    private static Optional<String> runRingProbeTrial(BiomeGraph graph, long worldSeed, double centerX, double centerZ) {
        try {
            SeedMeshCache cache = new SeedMeshCache(graph, GRID_CONFIG, worldSeed);
            HexGrid.AxialPos centerAxial = HexGrid.nearestAxialPos(new Point2D(centerX, centerZ), HEX_SIZE_BLOCKS);

            for (int[] delta : AXIAL_NEIGHBOR_DELTAS) {
                HexGrid.AxialPos neighborAxial = new HexGrid.AxialPos(centerAxial.q() + delta[0], centerAxial.r() + delta[1]);
                Point2D neighborCenter = HexGrid.centerOf(neighborAxial, HEX_SIZE_BLOCKS);
                cache.biomeAt(GRID_CONFIG.cellContaining(neighborCenter.x(), neighborCenter.z()));
            }

            Point2D centerPoint = HexGrid.centerOf(centerAxial, HEX_SIZE_BLOCKS);
            cache.biomeAt(GRID_CONFIG.cellContaining(centerPoint.x(), centerPoint.z()));
            return Optional.empty();
        } catch (IllegalStateException e) {
            return Optional.of(String.valueOf(e.getMessage()));
        }
    }

    /** Short random walk of small steps, matching ordinary player exploration - two-attempt recovery's own intended common case. */
    private static Optional<String> runOrganicWalkTrial(BiomeGraph graph, long worldSeed, double centerX, double centerZ) {
        SeedMeshCache cache = new SeedMeshCache(graph, GRID_CONFIG, worldSeed);
        SplittableRandom walker = new SplittableRandom(worldSeed ^ 0x0DEA_DBEEFL);
        double x = centerX;
        double z = centerZ;
        try {
            cache.biomeAt(GRID_CONFIG.cellContaining(x, z));
            for (int step = 0; step < ORGANIC_WALK_STEPS; step++) {
                double angle = walker.nextDouble() * 2 * Math.PI;
                x += Math.cos(angle) * ORGANIC_WALK_STEP_BLOCKS;
                z += Math.sin(angle) * ORGANIC_WALK_STEP_BLOCKS;
                cache.biomeAt(GRID_CONFIG.cellContaining(x, z));
            }
            return Optional.empty();
        } catch (IllegalStateException e) {
            return Optional.of(String.valueOf(e.getMessage()));
        }
    }
}
