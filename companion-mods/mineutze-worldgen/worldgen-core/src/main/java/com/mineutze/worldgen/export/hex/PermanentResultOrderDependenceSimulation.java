package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.regionsolve.livepath.DecidedFaceStore;

import com.mineutze.worldgen.regionsolve.livepath.TriangleFieldGeometryRealization;

import com.mineutze.worldgen.regionsolve.livepath.DecidedVertexStore;

import com.mineutze.worldgen.regionsolve.livepath.SeedMeshCache;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Real, honest measurement (2026-09-11) of a question raised directly, not assumed:
 * {@code TriangleFieldGeometryRealizationTest.permanentResultsAgreeRegardlessOfExplorationOrderEvenWithTentativeGeometryRevision}
 * (in test sources) found exploring the SAME 3 points in different order can produce a
 * different PERMANENT biome, using a deliberately adversarial synthetic setup: tiny
 * 4-biome hand-built graph, 3 points spaced closer (240-280 blocks) than real hex size
 * (325 blocks), forcing cluster overlap. Proves the underlying MECHANISM is real (which
 * neighbors are already-decided when a cluster forms depends on exploration history - a
 * property of CSP's own two-attempt strategy, not introduced this session) - does NOT
 * prove this is practical/production-relevant for the real 48-biome catalog under
 * realistic exploration.
 *
 * <p>This tool answers that honestly, reusing {@link DeadEndFrequencySimulation}'s
 * already-trusted methodology (same {@code ORGANIC_WALK} step pattern, real {@link
 * ModBiomeGraph#OVERWORLD} catalog, same {@link SplittableRandom}-based parallel-yet-
 * deterministic trial design): for many trials, generate one organic-walk sequence of
 * points, answer EVERY point in that sequence through two SEPARATE independent {@link
 * SeedMeshCache} instances (same worldSeed, same points) - once forward, once reversed -
 * compare each point's final (permanent) biome between the two.
 */
public final class PermanentResultOrderDependenceSimulation {

    private static final double HEX_SIZE_BLOCKS = 325; // matches TriangleFieldGeometryRealization, kept in sync manually - same convention as DeadEndFrequencySimulation's copy
    private static final MacroGridConfig GRID_CONFIG = MacroGridConfig.DEFAULT;
    private static final int TRIALS = 20_000;
    private static final double CENTER_SPREAD_BLOCKS = 2_000_000; // same reasoning as DeadEndFrequencySimulation: spread trials far enough apart that cluster growth never overlaps
    private static final int WALK_STEPS = 20;
    private static final double WALK_STEP_BLOCKS = 300; // matches DeadEndFrequencySimulation.ORGANIC_WALK_STEP_BLOCKS - established "ordinary exploration" spacing, not adversarially tight
    private static final int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    private PermanentResultOrderDependenceSimulation() {
    }

    /** One trial's own walk sequence and both orders' results. */
    private record TrialOutcome(long worldSeed, List<Point2DPair> points, List<BiomeId> forwardResults, List<BiomeId> reverseResults) {
        boolean anyDisagreement() {
            for (int i = 0; i < forwardResults.size(); i++) {
                if (!forwardResults.get(i).equals(reverseResults.get(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Point2DPair(double x, double z) {
    }

    public static void main(String[] args) throws InterruptedException {
        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        System.out.println("Using " + THREAD_COUNT + " worker threads.");
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            SplittableRandom scenarioRandom = new SplittableRandom(20260911);
            ExecutorCompletionService<TrialOutcome> completionService = new ExecutorCompletionService<>(executor);
            for (int i = 0; i < TRIALS; i++) {
                SplittableRandom trialRandom = scenarioRandom.split();
                completionService.submit(() -> runOneTrial(graph, trialRandom));
            }

            int disagreements = 0;
            long startNanos = System.nanoTime();
            for (int completed = 1; completed <= TRIALS; completed++) {
                TrialOutcome outcome;
                try {
                    outcome = completionService.take().get();
                } catch (Exception e) {
                    throw new IllegalStateException("PermanentResultOrderDependenceSimulation: a trial task itself failed unexpectedly (not a disagreement - a real bug in the simulation harness)", e);
                }
                if (outcome.anyDisagreement()) {
                    disagreements++;
                    System.out.println("  DISAGREEMENT: worldSeed=" + outcome.worldSeed() + " forward=" + outcome.forwardResults() + " reverse=" + outcome.reverseResults());
                }
                if (completed % 1000 == 0) {
                    System.out.println("  " + completed + "/" + TRIALS + " (" + disagreements + " disagreements so far)");
                }
            }
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            System.out.println();
            System.out.printf("PERMANENT RESULT ORDER-DEPENDENCE: %d/%d trials (%.4f%%) disagreed between forward and reverse exploration order, %.1fs total%n",
                disagreements, TRIALS, 100.0 * disagreements / TRIALS, seconds);
        } finally {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
        }
    }

    /**
     * One organic walk (same shape as {@code DeadEndFrequencySimulation.runOrganicWalkTrial}),
     * answered forward through one fresh cache, reversed through a second independent fresh
     * cache - same worldSeed, same points, only ORDER differs.
     */
    private static TrialOutcome runOneTrial(BiomeGraph graph, SplittableRandom trialRandom) {
        long worldSeed = trialRandom.nextLong();
        double centerX = (trialRandom.nextDouble() * 2 - 1) * CENTER_SPREAD_BLOCKS;
        double centerZ = (trialRandom.nextDouble() * 2 - 1) * CENTER_SPREAD_BLOCKS;

        List<Point2DPair> points = new ArrayList<>();
        double x = centerX;
        double z = centerZ;
        points.add(new Point2DPair(x, z));
        for (int step = 0; step < WALK_STEPS; step++) {
            double angle = trialRandom.nextDouble() * 2 * Math.PI;
            x += Math.cos(angle) * WALK_STEP_BLOCKS;
            z += Math.sin(angle) * WALK_STEP_BLOCKS;
            points.add(new Point2DPair(x, z));
        }

        List<BiomeId> forwardResults = new ArrayList<>();
        SeedMeshCache forwardCache = new SeedMeshCache(graph, GRID_CONFIG, worldSeed, new DecidedFaceStore(), new DecidedVertexStore());
        for (Point2DPair point : points) {
            forwardResults.add(forwardCache.biomeAt(GRID_CONFIG.cellContaining(point.x(), point.z())));
        }

        List<BiomeId> reverseResultsInWalkOrder = new ArrayList<>();
        SeedMeshCache reverseCache = new SeedMeshCache(graph, GRID_CONFIG, worldSeed, new DecidedFaceStore(), new DecidedVertexStore());
        for (int i = points.size() - 1; i >= 0; i--) {
            reverseResultsInWalkOrder.add(0, reverseCache.biomeAt(GRID_CONFIG.cellContaining(points.get(i).x(), points.get(i).z())));
        }

        return new TrialOutcome(worldSeed, points, forwardResults, reverseResultsInWalkOrder);
    }
}
