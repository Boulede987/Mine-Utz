package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Set;

/**
 * Laws a graph states now, to tell which ranked obstacles an edit already changed: a pair made legal, a size range
 * changed, a biome removed. Same coarse size model as the Solve check, or every size law would look changed.
 */
final class StatedLaws {

    private final RawRuleTable table;
    private final Set<GraphLaw> laws;
    private final Set<BiomeId> biomes;

    private StatedLaws(RawRuleTable table, Set<GraphLaw> laws, Set<BiomeId> biomes) {
        this.table = table;
        this.laws = laws;
        this.biomes = biomes;
    }

    /** @throws RankingUnavailable the graph cannot be sized as a plan (the editor shows why instead of a dropped request) */
    static StatedLaws of(BiomeGraphData catalog, PlanSettings settings) {
        RawRuleTable table = RawRuleTable.from(catalog);
        Set<GraphLaw> laws;
        try {
            laws = Set.copyOf(GraphLaws.of(table, MultigridPatternGenerator.coarseModelOf(table, settings)));
        } catch (IllegalArgumentException unsizable) {
            throw new RankingUnavailable("The current graph cannot be sized for a plan, so the saved ranking cannot be checked against it: " + unsizable.getMessage());
        }
        return new StatedLaws(table, laws, Set.copyOf(table.allBiomeIds()));
    }

    boolean states(Obstacle obstacle) {
        return switch (obstacle) {
            case LawObstacle law -> states(law.law());
            case MissingBiome missing -> biomes.contains(missing.biome());
            case ShapeLimit shape -> biomes.contains(shape.biome());
        };
    }

    /** Pairs by legality, not set lookup: a ranked pair's biome order is not GraphLaws' order. */
    private boolean states(GraphLaw law) {
        if (law instanceof IllegalPairLaw pair) {
            return biomes.contains(pair.first()) && biomes.contains(pair.second()) && !table.isLegal(pair.first(), pair.second());
        }
        return laws.contains(law);
    }
}
