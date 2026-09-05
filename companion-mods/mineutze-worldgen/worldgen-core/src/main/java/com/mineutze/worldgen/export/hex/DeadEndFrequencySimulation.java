package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.SeedMeshCache;
import com.mineutze.worldgen.regionsolve.topology.HexGrid;
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
 * Empirical measurement of the real dead-end frequency the tentative/permanent
 * redesign leaves behind (see this whole redesign's own plan doc, and the chat
 * investigation that led to it: a mathematically-derived 100% guarantee is provably
 * impossible for a themed 48-biome catalog — {@code BiomeGraph
 * .requireEveryLocalNeighborhoodSolvable} already proves 23.6% of realizable 6-rings
 * have no universal candidate — so the honest question is HOW OFTEN a real query
 * pattern actually realizes one, not whether it can happen in principle).
 *
 * <p><b>Two scenarios, deliberately different adversarial levels:</b>
 * <ul>
 *   <li>{@code RING_PROBE} — the worst case this redesign's own two-attempt recovery
 *   CANNOT protect against: all 6 real neighbors of a center face are queried DIRECTLY
 *   (each individually promoted PERMANENT, a true hard wall) before the center itself is
 *   ever touched — exactly the structure-placement ring-search pattern that caused the
 *   ORIGINAL worldSeed=42 crash earlier this session. This measures the graph's own raw
 *   23.6% bad-ring rate as it actually manifests through real biome placement (not a
 *   uniform combinatorial sample over the abstract rule graph).</li>
 *   <li>{@code ORGANIC_WALK} — a random walk of small steps, matching ordinary
 *   exploration. The two-attempt recovery DOES help here (most nearby context stays
 *   tentative, freely revisable), so this should show a much lower rate — the practical
 *   benefit of the redesign for real gameplay. First run measured 1/10,000 (0.01%),
 *   confirmed a genuine CSP dead end (worldSeed=291692674858800685) via this class's own
 *   exception-message capture, not the unrelated extreme-coordinate artifact found
 *   earlier — see this class's own git history / the plan doc for the full story.</li>
 * </ul>
 *
 * <p>Every trial uses a FRESH {@link SeedMeshCache} (no cross-trial reuse) so each one is
 * an independent sample — deliberately not thousands of small solves against one shared
 * cache, which would mostly measure cache-hit behavior, not fresh dead-end risk. Each
 * trial is also fully independent of every OTHER trial (its own private in-memory stores,
 * nothing shared) — an embarrassingly parallel workload, run here across a thread pool
 * instead of sequentially.
 *
 * <p><b>Parallel + reproducible, not one or the other:</b> a single shared {@code Random}
 * consumed sequentially across worker threads would either race or make the exact set of
 * trials depend on nondeterministic thread scheduling — bad for reproducing a specific
 * failure later. Instead, one {@link SplittableRandom} per SCENARIO is {@code split()} on
 * the MAIN thread, once per trial, in a fixed sequential order, before that trial's own
 * split-off instance is handed to a worker thread — the standard, documented pattern for
 * deterministic parallel decomposition with this class. Trial #4,872 always gets the same
 * (worldSeed, center) regardless of how the thread pool happens to schedule it.
 */
public final class DeadEndFrequencySimulation {

    // Matches TriangleFieldGeometryRealization.HEX_SIZE_BLOCKS - kept in sync manually,
    // same established convention as ModBiomeGraphTest's own solveAndRasterizeOverworld.
    private static final double HEX_SIZE_BLOCKS = 325;
    private static final MacroGridConfig GRID_CONFIG = MacroGridConfig.DEFAULT;

    // Pointy-top axial neighbor deltas, in real ring order (matches HexGrid's own
    // pointy-top convention - see that class's own centerOf doc).
    private static final int[][] AXIAL_NEIGHBOR_DELTAS = {
        {1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}
    };

    private static final int TRIALS_PER_SCENARIO = 100_000;
    // Spread centers across a huge area so no two trials' own 2500-block cluster caps
    // could ever overlap and quietly reuse each other's work - each trial must be a
    // genuinely independent, fresh sample. Comfortably within vanilla's own default
    // ~30,000,000-block world border (real gameplay never approaches this either way) -
    // a smaller diagnostic run at 500,000 first confirmed a SEPARATE, unrelated crash
    // ("not covered by any nearby face") at 50,000,000 was a real-but-low-priority
    // floating-point artifact specific to unrealistic extreme coordinates, not a
    // fundamental rasterizer/closure bug - zero repro at this saner spread.
    private static final double CENTER_SPREAD_BLOCKS = 2_000_000;
    private static final int ORGANIC_WALK_STEPS = 20;
    private static final double ORGANIC_WALK_STEP_BLOCKS = 300;

    // Leaves one logical core free for the OS/rest of the machine - this is a long batch
    // job, not latency-sensitive interactive work, so using most/all cores is fine, but
    // starving the whole machine for the run's own duration isn't necessary to get the
    // real benefit. Measured on the machine this was built against: 6 physical/12 logical
    // cores - real speedup will be well under the full logical-core count (GC and memory
    // bandwidth get shared under real allocation pressure, and hyperthreading gives less
    // than 2x per physical core for branchy, allocation-heavy work like CSP backtracking),
    // not a reason to avoid parallelizing, just a reason not to over-promise the exact factor.
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

    /** One captured failure - {@code worldSeed}/center let it be reproduced exactly later; {@code message} is the real exception text, not just a count. */
    private record Failure(long worldSeed, double centerX, double centerZ, String message) {
    }

    /** One trial's full outcome, computed entirely on a worker thread - collected back on the main thread with no further synchronization needed (see this class's own doc on why only the RNG source needed care). */
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
        /** Empty if this trial completed with no dead end; the REAL exception message otherwise (never just a boolean — see this class's own doc for why that lost real diagnostic value the first time this ran). */
        Optional<String> tryRun(BiomeGraph graph, long worldSeed, double centerX, double centerZ);
    }

    /**
     * Submits all {@code trialCount} trials up front (each with its own {@code
     * SplittableRandom.split()}-derived, deterministic parameters — see this class's own
     * doc), then collects results in COMPLETION order via {@link ExecutorCompletionService}
     * (not submission order) so progress reporting reflects real, live progress under
     * parallelism rather than lagging behind whichever task happens to be submitted, but
     * slowest, first. Aggregation itself (the {@code deadEnds} list) only ever runs on the
     * MAIN thread here (inside the single sequential {@code take()} loop below) — no
     * concurrent collection needed, despite the WORK being fully parallel.
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
     * Queries all 6 real neighbors of the center's own nearest face DIRECTLY (each
     * becomes permanent, a true hard wall), then queries the center itself — the exact
     * "structure-search ring" pattern the two-attempt recovery cannot protect against,
     * since every neighbor is genuinely, individually permanent by the time the center is
     * touched.
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

    /** A short random walk of small steps, matching ordinary player exploration — the two-attempt recovery's own intended common case. */
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
