package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces the identity pattern for one world: a legal assignment of a biome to every cell of a
 * {@link ToroidalGrid}, covering every catalog biome (2026-09-19). Deterministic - the same world
 * seed always yields the same pattern - and limited by a step budget rather than wall-clock time,
 * so the outcome never depends on how busy the machine was.
 *
 * <p>The budget is a safety net, not a tuning knob: across 2,000 real seeds at 24x24 and 28x28 the
 * slowest solve took about 3.7 million steps, and the default is roughly ten times that. A world
 * seed never gets near it. It exists so that a catalog edited into an unsolvable state fails
 * loudly with everything needed to reproduce the failure, instead of hanging world creation.
 */
final class ToroidalPatternGenerator {

    static final long DEFAULT_MAX_STEPS = 40_000_000L;

    /** Same restart threshold the reliability sweeps used; measured to barely matter (see the tuning sweep) - 0 of 400 runs needed a restart at 1,000,000. */
    private static final long STALL_STEPS_BEFORE_RESTART = 500_000L;

    /** Keeps this generator's random stream separate from anything else that is also seeded from the raw world seed. */
    private static final long WORLD_SEED_SALT = 0x5A17_E6D3_09B1_44C7L;

    private static final int STUCK_EDGES_QUOTED = 10;

    private ToroidalPatternGenerator() {
    }

    static Map<ToroidalPos, BiomeId> generate(RawRuleTable table, ToroidalGrid grid, long worldSeed) {
        return generate(table, grid, worldSeed, DEFAULT_MAX_STEPS);
    }

    /** Throws {@link IllegalStateException}, carrying the world seed, grid size, budget and the closest state's stuck edges, if no legal pattern is found within {@code maxSteps}. */
    static Map<ToroidalPos, BiomeId> generate(RawRuleTable table, ToroidalGrid grid, long worldSeed, long maxSteps) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("ToroidalPatternGenerator: maxSteps must be positive, got " + maxSteps);
        }
        ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.searchWithinSteps(
            table, grid, worldSeed ^ WORLD_SEED_SALT, maxSteps, STALL_STEPS_BEFORE_RESTART, Set.copyOf(table.allBiomeIds())
        );
        if (result.assignment() == null) {
            throw new IllegalStateException(failureMessage(table, grid, worldSeed, maxSteps, result));
        }
        return result.assignment();
    }

    private static String failureMessage(RawRuleTable table, ToroidalGrid grid, long worldSeed, long maxSteps, ToroidalIdentityLocalSearch.Result result) {
        List<String> stuckEdges = ToroidalIdentityLocalSearch.illegalEdgeDescriptions(table, grid, result.closestAssignment());
        return "ToroidalPatternGenerator: no legal pattern within " + maxSteps + " steps (worldSeed=" + worldSeed + ", grid=" + grid.width() + "x" + grid.height()
            + ", biomes=" + table.allBiomeIds().size() + "). Closest state reached " + result.bestIllegalEdges() + " illegal edges and " + result.bestMissingBiomes()
            + " missing biomes after " + result.steps() + " steps; first stuck edges: " + stuckEdges.subList(0, Math.min(STUCK_EDGES_QUOTED, stuckEdges.size()))
            + ". Most likely cause: the biome graph was edited into a state with no legal 66-biome tiling, or the grid is too small for it.";
    }
}
