package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.io.IOException;
import java.util.Optional;

/**
 * Drives {@link PeriodicCoherenceProof}'s own construction search from a real worldSeed
 * instead of the fixed internal {@code RANDOM_SEED} constant - different seeds
 * deterministically produce different periodic witnesses (2026-09-17 user direction:
 * "compute a patch using a random seed," toward per-seed pattern variety instead of one
 * fixed witness repeated for every world - see {@code PatternDiversityLowerBound} for the
 * separate, quantified proof that the space of such patterns is genuinely large).
 *
 * <p>XORs the raw worldSeed with a dedicated decorrelation constant before use - same
 * established convention as {@code DeadEndFrequencySimulation}'s own ORGANIC_WALK walker
 * ({@code worldSeed ^ 0x0DEA_DBEEFL}) - so this subsystem's own randomness doesn't silently
 * correlate with anything else in the mod that also derives behavior from the same raw
 * worldSeed.
 *
 * <p><b>Not the shipped generator</b> - this is a proof/demonstration tool for the periodic-
 * repeat idea discussed 2026-09-17, not wired into any live path. A search can be
 * INCONCLUSIVE for a given seed (bounded search, same honesty discipline as {@link
 * PeriodicCoherenceProof} itself) - never silently treated as "this seed has no valid
 * pattern," only "this bounded search didn't find one this time."
 */
public final class SeedDrivenPatchGenerator {

    private static final long DECORRELATION_CONSTANT = 0x9A7C_11EDL;

    private SeedDrivenPatchGenerator() {
    }

    /** @return the witness found for this worldSeed, or empty if this bounded search was inconclusive (not a claim of impossibility). */
    public static Optional<PeriodicRowLayout> generate(RawRuleTable table, long worldSeed) {
        PeriodicCoherenceProof.WitnessSearchResult result = PeriodicCoherenceProof.findWitness(table, worldSeed ^ DECORRELATION_CONSTANT);
        return Optional.ofNullable(result.layout());
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);

        long[] sampleWorldSeeds = {1L, 2L, 42L, 2026L, -8675309L};
        for (long worldSeed : sampleWorldSeeds) {
            Optional<PeriodicRowLayout> layout = generate(table, worldSeed);
            if (layout.isEmpty()) {
                System.out.println("worldSeed=" + worldSeed + ": INCONCLUSIVE (no witness found within this search's own bounded budget)");
                continue;
            }
            PeriodicRowLayout found = layout.get();
            System.out.println(
                "worldSeed=" + worldSeed + ": rowCount=" + found.rows().size() + ", circumferenceBlocks=" + found.circumferenceBlocks()
                    + ", row0FirstBiome=" + found.rows().get(0).cells().get(0).biomeId()
            );
        }
    }
}
