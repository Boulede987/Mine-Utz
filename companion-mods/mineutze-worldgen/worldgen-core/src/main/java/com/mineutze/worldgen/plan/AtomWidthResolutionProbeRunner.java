package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures whether the joint identity+size search still converges reasonably at a much finer grid
 * resolution (2026-09-26): a real 9x increase in cell count ({@code gridSize=120,
 * atomWidthBlocks=100} instead of the shipped {@code 40/300}) that preserves the world's physical
 * tile size exactly ({@code 120 * 0.866 * 100} equals {@code 40 * 0.866 * 300}, about 10,392 blocks
 * across).
 *
 * <p>A first bounded run (today's schedule, unchanged) found solving nowhere near converged even at
 * production's full step budget (bestCost 442, not 0, for world seed 42 at 40,000,000 steps) - real
 * re-tuning is needed, not just more time. This runner now A/B-compares candidate {@link
 * ToroidalSizedLocalSearch.Schedule}s directly by best-cost-reached, at a REDUCED, bounded step/seed
 * budget so the comparison itself stays cheap: the schedule's {@code cycleSteps} (how many steps
 * between each sawtooth reheat) was tuned at 1,600 cells; at 9x the cells, each cycle sweeps a much
 * smaller fraction of the grid before cooling back down, so the first candidate tested is that same
 * cycle length scaled by the same 9x.
 *
 * <p>Deliberately does NOT touch {@link PlanSettings#DEFAULT} or the calibration manifest - this is
 * still a probe, not a re-tune of production settings.
 */
public final class AtomWidthResolutionProbeRunner {

    private static final int PROBE_GRID_SIZE = 120;
    private static final double PROBE_ATOM_WIDTH_BLOCKS = 100.0;
    private static final long PROBE_STEPS_PER_SEED = 5_000_000L;
    private static final int PROBE_MAX_SEEDS = 20;
    private static final long[] WORLD_SEEDS = {42L, 1094L, 20260920L, -7311L, 424242L};

    private record ScheduleCandidate(String label, ToroidalSizedLocalSearch.Schedule schedule) {
    }

    private static final List<ScheduleCandidate> CANDIDATES = List.of(
        new ScheduleCandidate("today's (cycleSteps=1,000,000)", ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE),
        new ScheduleCandidate("cycleSteps x9 (9,000,000)", new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 9_000_000))
    );

    private record SeedResult(long worldSeed, boolean solved, int bestCost, double seconds) {
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
            "PROBE grid " + PROBE_GRID_SIZE + "x" + PROBE_GRID_SIZE + " at atom width " + PROBE_ATOM_WIDTH_BLOCKS + " blocks (" + grid.cellCount() + " cells, "
                + (grid.cellCount() / (double) (40 * 40)) + "x production's 1600), budget " + PROBE_STEPS_PER_SEED + " steps x " + PROBE_MAX_SEEDS
                + " seeds per world seed, " + threads + " threads"
        );

        Map<String, List<SeedResult>> resultsByCandidate = new LinkedHashMap<>();
        for (ScheduleCandidate candidate : CANDIDATES) {
            resultsByCandidate.put(candidate.label(), runCandidate(table, grid, model, candidate, threads));
        }
        reportComparison(resultsByCandidate);
    }

    private static List<SeedResult> runCandidate(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, ScheduleCandidate candidate, int threads) {
        System.out.println();
        System.out.println("SCHEDULE " + candidate.label());
        List<SeedResult> results = new ArrayList<>();
        for (long worldSeed : WORLD_SEEDS) {
            results.add(runSeed(table, grid, model, candidate, worldSeed, threads));
        }
        return results;
    }

    private static SeedResult runSeed(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, ScheduleCandidate candidate, long worldSeed, int threads) {
        long start = System.nanoTime();
        long baseSeed = ToroidalSizedPatternGenerator.baseSeedOf(worldSeed);
        ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model, baseSeed, PROBE_MAX_SEEDS, PROBE_STEPS_PER_SEED, candidate.schedule(), threads);
        double seconds = secondsSince(start);
        int bestCost = outcome.result().bestCost();
        System.out.println(
            "SEED " + worldSeed + ": " + (outcome.solved() ? "SOLVED" : "not solved") + ", best cost " + bestCost + ", " + String.format(Locale.ROOT, "%.1f", seconds) + "s"
        );
        return new SeedResult(worldSeed, outcome.solved(), bestCost, seconds);
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static void reportComparison(Map<String, List<SeedResult>> resultsByCandidate) {
        System.out.println();
        System.out.println("RESULT best cost per seed, by schedule (lower is closer to solved; 0 = solved):");
        for (int index = 0; index < WORLD_SEEDS.length; index++) {
            reportSeedRow(index, resultsByCandidate);
        }
        reportAverageCost(resultsByCandidate);
    }

    private static void reportSeedRow(int index, Map<String, List<SeedResult>> resultsByCandidate) {
        StringBuilder line = new StringBuilder("  seed " + WORLD_SEEDS[index] + ":  ");
        for (Map.Entry<String, List<SeedResult>> entry : resultsByCandidate.entrySet()) {
            appendSeedCost(line, entry.getKey(), entry.getValue().get(index));
        }
        System.out.println(line);
    }

    private static void appendSeedCost(StringBuilder line, String label, SeedResult result) {
        line.append(label).append("=").append(result.bestCost()).append(result.solved() ? " (SOLVED)" : "").append("   ");
    }

    private static void reportAverageCost(Map<String, List<SeedResult>> resultsByCandidate) {
        System.out.println();
        for (Map.Entry<String, List<SeedResult>> entry : resultsByCandidate.entrySet()) {
            double averageCost = entry.getValue().stream().mapToInt(SeedResult::bestCost).average().orElse(0);
            System.out.println("RESULT " + entry.getKey() + ": average best cost " + String.format(Locale.ROOT, "%.1f", averageCost) + " across " + WORLD_SEEDS.length + " seeds");
        }
    }
}
