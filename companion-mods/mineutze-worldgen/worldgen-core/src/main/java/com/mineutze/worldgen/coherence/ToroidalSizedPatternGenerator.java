package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.SplittableRandom;

/**
 * One fresh, valid biome pattern per world seed (2026-09-19): identity, size and shape solved
 * together on a fine torus, from the world seed alone. Deterministic - the same world seed always
 * gives the same pattern, whatever the machine or thread count - and every pattern it returns has
 * passed {@link ToroidalSizedLocalSearch#verify}.
 *
 * <p><b>Why many short attempts</b>: one seed of the search solves the real catalog only some of
 * the time (about 21% within 40 million steps at ratio 1.3 on 40x40) and a seed that has not
 * solved by then almost never does later - the solve rate flattens, it does not keep climbing.
 * So the world seed is expanded into a sequence of independent attempts, each capped at {@code
 * stepsPerSeed}, run in parallel by {@link ParallelSeedSearch}; the LOWEST attempt index that
 * solves wins, which is what keeps the result independent of scheduling. It fails only if all
 * {@code maxSeeds} attempts fail, with probability about (1 - solve rate)^maxSeeds.
 */
final class ToroidalSizedPatternGenerator {

    static final long DEFAULT_STEPS_PER_SEED = 40_000_000L;
    static final int DEFAULT_MAX_SEEDS = 200;
    static final ToroidalSizedLocalSearch.Schedule DEFAULT_SCHEDULE = new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 1_000_000);

    private static final long WORLD_SEED_SALT = 0x2C1B_7F49_A8D3_E605L;
    private static final int STUCK_PAIRS_IN_FAILURE_MESSAGE = 5;

    private ToroidalSizedPatternGenerator() {
    }

    /** A solved pattern and which attempt (0-based) of the world seed's sequence produced it. */
    record Generated(Map<ToroidalPos, BiomeId> pattern, int seedIndex) {
    }

    static Generated generate(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long worldSeed, int threadCount) {
        return generate(table, grid, model, worldSeed, DEFAULT_STEPS_PER_SEED, DEFAULT_MAX_SEEDS, DEFAULT_SCHEDULE, threadCount);
    }

    static Generated generate(
        RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long worldSeed, long stepsPerSeed, int maxSeeds,
        ToroidalSizedLocalSearch.Schedule schedule, int threadCount
    ) {
        if (stepsPerSeed <= 0 || maxSeeds <= 0 || threadCount <= 0) {
            throw new IllegalArgumentException(
                "ToroidalSizedPatternGenerator: stepsPerSeed, maxSeeds and threadCount must be positive, got " + stepsPerSeed + ", " + maxSeeds + ", " + threadCount
            );
        }
        long baseSeed = baseSeedOf(worldSeed);
        ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model, baseSeed, maxSeeds, stepsPerSeed, schedule, threadCount);
        if (!outcome.solved()) {
            throw new IllegalStateException(failureMessage(table, grid, model, worldSeed, stepsPerSeed, maxSeeds, outcome));
        }
        return new Generated(outcome.result().assignment(), (int) (outcome.seed() - baseSeed));
    }

    /** The first of the world seed's attempt seeds; attempt {@code i} uses {@code baseSeedOf(worldSeed) + i}. Scrambled so that neighboring world seeds get unrelated ranges. */
    static long baseSeedOf(long worldSeed) {
        return new SplittableRandom(worldSeed ^ WORLD_SEED_SALT).nextLong();
    }

    private static String failureMessage(
        RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long worldSeed, long stepsPerSeed, int maxSeeds, ParallelSeedSearch.Outcome outcome
    ) {
        Map<ToroidalPos, BiomeId> closest = outcome.result().closestAssignment();
        ToroidalSizedLocalSearch.Breakdown breakdown = ToroidalSizedLocalSearch.breakdown(table, grid, model, closest);
        StringBuilder stuckPairs = new StringBuilder();
        ToroidalSizedLocalSearch.illegalPairCounts(table, grid, closest).entrySet().stream()
            .sorted((first, second) -> second.getValue() - first.getValue())
            .limit(STUCK_PAIRS_IN_FAILURE_MESSAGE)
            .forEach(entry -> stuckPairs.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" edges"));
        return "ToroidalSizedPatternGenerator: no valid pattern for world seed " + worldSeed + " on a " + grid.width() + "x" + grid.height() + " torus with " + table.allBiomeIds().size()
            + " biomes after " + maxSeeds + " seeds of " + stepsPerSeed + " steps each. The closest attempt has cost " + outcome.result().bestCost() + " (illegal edges "
            + breakdown.illegalEdges() + ", cluster violation " + breakdown.clusterViolation() + ", missing biomes " + breakdown.missingBiomes() + "); its stuck pairs:" + stuckPairs;
    }
}
