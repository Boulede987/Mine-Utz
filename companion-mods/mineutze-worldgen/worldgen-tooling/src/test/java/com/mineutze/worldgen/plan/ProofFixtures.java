package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;

/** Small catalogs and settings for proof-engine tests. */
final class ProofFixtures {

    static final BiomeId E = new BiomeId("test", "e");

    /** Coarse 8x8@300, no fine stage: the same small grid WorldBiomePlanTest proves solvable. */
    static final PlanSettings SMALL_COARSE = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);

    private ProofFixtures() {
    }

    static CountingModel countingModelOf(BiomeGraphData catalog, PlanSettings settings) {
        return CountingModel.of(RawRuleTable.from(catalog), settings);
    }

    /** A border requirement no patch can meet in a 8x8 grid - built as a law directly, since the generator itself would reject it in a catalog. */
    static RequiredBorderLaw impossibleBorder(BiomeId biome, BiomeId partner) {
        return new RequiredBorderLaw(biome, new ResolvedBorderRequirement.Share(partner.toString(), List.of(partner), 500));
    }

    static List<GraphLaw> lawsOf(BiomeGraphData catalog, PlanSettings settings) {
        RawRuleTable table = RawRuleTable.from(catalog);
        return GraphLaws.of(table, MultigridPatternGenerator.coarseModelOf(table, settings));
    }
}
