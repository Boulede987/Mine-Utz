package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.List;

/** Every law of a graph a user can change: size ranges, required borders, illegal pairs. */
final class GraphLaws {

    private GraphLaws() {
    }

    static List<GraphLaw> of(RawRuleTable table, ClusterSizeModel model) {
        List<BiomeId> biomes = table.allBiomeIds();
        List<GraphLaw> laws = new ArrayList<>();
        biomes.forEach(biome -> laws.add(new SizeRangeLaw(biome, model.minCells(biome), model.maxCells(biome))));
        biomes.forEach(biome -> table.requirementOf(biome).ifPresent(requirement -> laws.add(new RequiredBorderLaw(biome, requirement))));
        for (int firstIndex = 0; firstIndex < biomes.size(); firstIndex++) {
            addIllegalPairsFrom(table, biomes, firstIndex, laws);
        }
        return laws;
    }

    /** Unordered pairs only: legality is symmetric (RawRuleTable stores unordered pairs). */
    private static void addIllegalPairsFrom(RawRuleTable table, List<BiomeId> biomes, int firstIndex, List<GraphLaw> laws) {
        for (int secondIndex = firstIndex + 1; secondIndex < biomes.size(); secondIndex++) {
            if (!table.isLegal(biomes.get(firstIndex), biomes.get(secondIndex))) {
                laws.add(new IllegalPairLaw(biomes.get(firstIndex), biomes.get(secondIndex)));
            }
        }
    }
}
