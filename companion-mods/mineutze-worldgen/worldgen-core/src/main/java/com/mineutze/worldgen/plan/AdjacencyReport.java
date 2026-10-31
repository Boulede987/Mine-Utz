package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The neighborhood of each biome a failed pattern is stuck on (2026-10-04): how many biomes it may
 * legally touch, how many of those it actually touches, how many illegal edges it has, and which of
 * its legal partners are not in the pattern at all. A biome whose legal partners are absent or
 * untouched is blocked by its neighborhood, not by its own size.
 */
final class AdjacencyReport {

    static final int ABSENT_NAMES_SHOWN = 5;

    private AdjacencyReport() {
    }

    /** The section for the failure message, or the empty string when no biome is stuck. */
    static String of(RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern) {
        Set<BiomeId> stuck = ToroidalSizedLocalSearch.biomesOnIllegalEdges(table, grid, pattern);
        if (stuck.isEmpty()) {
            return "";
        }
        Map<BiomeId, Map<BiomeId, Integer>> edgesFrom = edgeCountsByBiome(grid, pattern);
        Set<BiomeId> present = Set.copyOf(pattern.values());
        StringBuilder section = new StringBuilder(
            "\nNeighborhood of each stuck biome (legal partners, touched, illegal edges, absent legal partners):"
        );
        stuck.stream().sorted().forEach(biome -> section.append("\n  ").append(lineOf(table, biome, edgesFrom.getOrDefault(biome, Map.of()), present)));
        return section.toString();
    }

    private static Map<BiomeId, Map<BiomeId, Integer>> edgeCountsByBiome(ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern) {
        Map<BiomeId, Map<BiomeId, Integer>> edgesFrom = new HashMap<>();
        for (Map.Entry<ToroidalPos, BiomeId> cell : pattern.entrySet()) {
            for (ToroidalPos neighbor : grid.neighborsOf(cell.getKey())) {
                BiomeId neighborBiome = pattern.get(neighbor);
                if (!cell.getValue().equals(neighborBiome)) {
                    edgesFrom.computeIfAbsent(cell.getValue(), biome -> new HashMap<>()).merge(neighborBiome, 1, Integer::sum);
                }
            }
        }
        return edgesFrom;
    }

    private static String lineOf(RawRuleTable table, BiomeId biome, Map<BiomeId, Integer> edgesToBiome, Set<BiomeId> present) {
        List<BiomeId> legalPartners = table.allBiomeIds().stream().filter(other -> !other.equals(biome) && table.isLegal(biome, other)).toList();
        long touchedLegal = legalPartners.stream().filter(edgesToBiome::containsKey).count();
        int illegalEdges = edgesToBiome.entrySet().stream().filter(edge -> !table.isLegal(biome, edge.getKey())).mapToInt(Map.Entry::getValue).sum();
        List<BiomeId> absent = legalPartners.stream().filter(partner -> !present.contains(partner)).toList();
        return biome + ": " + legalPartners.size() + " legal partners, " + touchedLegal + " touched, " + illegalEdges + " illegal edges, absent legal partners "
            + absentText(absent);
    }

    private static String absentText(List<BiomeId> absent) {
        if (absent.isEmpty()) {
            return "none";
        }
        String names = absent.size() <= ABSENT_NAMES_SHOWN ? " " + absent : "";
        return absent.size() + names;
    }
}
