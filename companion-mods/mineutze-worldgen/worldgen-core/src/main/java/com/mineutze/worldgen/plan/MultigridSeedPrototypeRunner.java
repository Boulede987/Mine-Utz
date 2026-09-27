package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tests the multigrid hypothesis (2026-09-27, see {@code DECISIONS.md}) across several world seeds:
 * solve at production's own proven scale (40x40 cells, 300-block atoms), then upsample that solution
 * into a seed for the fine 120x120/100-block grid instead of starting fine resolution from a random
 * Voronoi partition. {@code gridSize}/{@code atomWidthBlocks} were chosen so a coarse cell aggregates
 * an exact 3x3 block of fine cells - the coarse solve IS today's production solve, unmodified.
 *
 * <p>The fine refinement pass retries with a different internal random seed (same structural,
 * coarse-derived seed) up to {@value #MAX_REFINEMENT_ATTEMPTS} times, taking the first attempt that
 * solves - the same "try several independent attempts, take whichever solves first" mechanism
 * production already uses at the coarse level ({@link ParallelSeedSearch}), just applied one level
 * deeper. Found necessary directly: one world seed (-7311) plateaued at cost 4 with attempt 0 alone,
 * unmoved by DOUBLING the step budget (20,000,000 -> 40,000,000, identical cost both times - a local
 * trap for that one random walk, not a global slowdown) but solved in 3.7s on the very next attempt.
 */
public final class MultigridSeedPrototypeRunner {

    private static final long[] WORLD_SEEDS = {42L, 1094L, 20260920L, -7311L, 424242L};
    private static final int COARSE_GRID_SIZE = 40;
    private static final double COARSE_ATOM_WIDTH_BLOCKS = 300.0;
    private static final int FINE_GRID_SIZE = 120;
    private static final double FINE_ATOM_WIDTH_BLOCKS = 100.0;
    private static final int AGGREGATION_FACTOR = FINE_GRID_SIZE / COARSE_GRID_SIZE;
    private static final long REFINEMENT_STEPS = 20_000_000L;
    private static final int MAX_REFINEMENT_ATTEMPTS = 5;
    private static final ToroidalSizedLocalSearch.Schedule REFINEMENT_SCHEDULE = new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 9_000_000);

    private record RefinementOutcome(boolean solved, int attemptsUsed, int bestCost) {
    }

    private record SeedResult(long worldSeed, double coarseSeconds, int seededCost, RefinementOutcome refinement, double refineSeconds) {
    }

    private MultigridSeedPrototypeRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData catalog = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(catalog);
        int threads = Runtime.getRuntime().availableProcessors();
        ToroidalGrid coarseGrid = new ToroidalGrid(COARSE_GRID_SIZE, COARSE_GRID_SIZE);
        ToroidalGrid fineGrid = new ToroidalGrid(FINE_GRID_SIZE, FINE_GRID_SIZE);
        ClusterSizeModel coarseModel = coarseModel(table);
        ClusterSizeModel fineModel = fineModel(table);

        System.out.println(
            "MULTIGRID " + WORLD_SEEDS.length + " world seeds: coarse " + COARSE_GRID_SIZE + "x" + COARSE_GRID_SIZE + "@" + COARSE_ATOM_WIDTH_BLOCKS
                + " blocks (production, unmodified) -> fine " + FINE_GRID_SIZE + "x" + FINE_GRID_SIZE + "@" + FINE_ATOM_WIDTH_BLOCKS
                + " blocks, aggregation factor " + AGGREGATION_FACTOR + ", refinement budget " + REFINEMENT_STEPS + " steps x up to " + MAX_REFINEMENT_ATTEMPTS + " attempts"
        );

        List<SeedResult> results = new ArrayList<>();
        for (long worldSeed : WORLD_SEEDS) {
            results.add(runSeed(table, coarseGrid, fineGrid, coarseModel, fineModel, worldSeed, threads));
        }
        reportSummary(results);
    }

    private static SeedResult runSeed(
        RawRuleTable table, ToroidalGrid coarseGrid, ToroidalGrid fineGrid, ClusterSizeModel coarseModel, ClusterSizeModel fineModel, long worldSeed, int threads
    ) {
        System.out.println();
        System.out.println("SEED " + worldSeed);
        long coarseStart = System.nanoTime();
        ToroidalSizedPatternGenerator.Generated coarseSolved = ToroidalSizedPatternGenerator.generate(table, coarseGrid, coarseModel, worldSeed, threads);
        double coarseSeconds = secondsSince(coarseStart);
        System.out.println("  coarse solved at attempt " + (coarseSolved.seedIndex() + 1) + " in " + String.format(Locale.ROOT, "%.1f", coarseSeconds) + "s");

        Map<ToroidalPos, BiomeId> seed = upsampleToFineSeed(fineGrid, coarseGrid, coarseSolved.pattern());
        int seededCost = ToroidalSizedLocalSearch.cost(table, fineGrid, fineModel, seed);
        System.out.println("  seeded (before refinement) cost: " + seededCost);

        long refineStart = System.nanoTime();
        RefinementOutcome outcome = refineWithRetries(table, fineGrid, fineModel, worldSeed, seed);
        double refineSeconds = secondsSince(refineStart);
        System.out.println(
            "  refined: " + (outcome.solved() ? "SOLVED (attempt " + outcome.attemptsUsed() + ")" : "not solved after " + outcome.attemptsUsed() + " attempts, best cost "
                + outcome.bestCost()) + " in " + String.format(Locale.ROOT, "%.1f", refineSeconds) + "s"
        );
        return new SeedResult(worldSeed, coarseSeconds, seededCost, outcome, refineSeconds);
    }

    /** Tries a different internal random seed (the same structural, coarse-derived seed each time) up to {@link #MAX_REFINEMENT_ATTEMPTS} times, taking the first that solves. */
    private static RefinementOutcome refineWithRetries(RawRuleTable table, ToroidalGrid fineGrid, ClusterSizeModel fineModel, long worldSeed, Map<ToroidalPos, BiomeId> seed) {
        int bestCostSoFar = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < MAX_REFINEMENT_ATTEMPTS; attempt++) {
            ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.searchFromSeed(
                table, fineGrid, fineModel, worldSeed + attempt, seed, REFINEMENT_STEPS, REFINEMENT_SCHEDULE, () -> false, ToroidalSizedLocalSearch.DEFAULT_RELABEL_PROBABILITY
            );
            if (result.assignment() != null) {
                return new RefinementOutcome(true, attempt + 1, 0);
            }
            bestCostSoFar = Math.min(bestCostSoFar, result.bestCost());
        }
        return new RefinementOutcome(false, MAX_REFINEMENT_ATTEMPTS, bestCostSoFar);
    }

    private static Map<ToroidalPos, BiomeId> upsampleToFineSeed(ToroidalGrid fineGrid, ToroidalGrid coarseGrid, Map<ToroidalPos, BiomeId> coarseSolution) {
        Map<ToroidalPos, BiomeId> seed = new LinkedHashMap<>();
        for (ToroidalPos finePos : fineGrid.allPositionsInFillOrder()) {
            assignUpsampledCell(seed, coarseGrid, coarseSolution, finePos);
        }
        return seed;
    }

    private static void assignUpsampledCell(Map<ToroidalPos, BiomeId> seed, ToroidalGrid coarseGrid, Map<ToroidalPos, BiomeId> coarseSolution, ToroidalPos finePos) {
        ToroidalPos coarsePos = coarseGrid.normalize(finePos.q() / AGGREGATION_FACTOR, finePos.r() / AGGREGATION_FACTOR);
        BiomeId biome = coarseSolution.get(coarsePos);
        if (biome == null) {
            throw new IllegalStateException("MultigridSeedPrototypeRunner: coarse solution has no biome for " + coarsePos + " (fine cell " + finePos + ")");
        }
        seed.put(finePos, biome);
    }

    private static void reportSummary(List<SeedResult> results) {
        System.out.println();
        long solvedCount = results.stream().filter(result -> result.refinement().solved()).count();
        System.out.println("RESULT " + solvedCount + " of " + results.size() + " world seeds fully solved (cost 0) within the refinement budget");
        for (SeedResult result : results) {
            reportSeedLine(result);
        }
        double totalSeconds = results.stream().mapToDouble(result -> result.coarseSeconds() + result.refineSeconds()).sum();
        System.out.println(
            "RESULT total wall time across all " + results.size() + " seeds: " + String.format(Locale.ROOT, "%.1f", totalSeconds) + "s ("
                + String.format(Locale.ROOT, "%.1f", totalSeconds / results.size()) + "s average per world)"
        );
    }

    private static void reportSeedLine(SeedResult result) {
        RefinementOutcome outcome = result.refinement();
        System.out.println(
            "RESULT   seed " + result.worldSeed() + ": " + (outcome.solved() ? "SOLVED (attempt " + outcome.attemptsUsed() + ")" : "NOT solved (best cost " + outcome.bestCost() + ")")
                + ", seeded cost " + result.seededCost() + ", refinement " + String.format(Locale.ROOT, "%.1f", result.refineSeconds()) + "s, coarse "
                + String.format(Locale.ROOT, "%.1f", result.coarseSeconds()) + "s"
        );
    }

    private static ClusterSizeModel coarseModel(RawRuleTable table) {
        return new ClusterSizeModel(table, COARSE_ATOM_WIDTH_BLOCKS, new ShapeRule(PlanSettings.DEFAULT.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    private static ClusterSizeModel fineModel(RawRuleTable table) {
        return new ClusterSizeModel(table, FINE_ATOM_WIDTH_BLOCKS, new ShapeRule(PlanSettings.DEFAULT.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }
}
