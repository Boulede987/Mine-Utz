package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Seed growth (see {@link GrowthEngine}) rarely tiles a region perfectly —
 * patches can leave small gaps between them where neither neighbor's
 * growth reached. Sweep leftover cells repeatedly (a filled cell can make
 * its own neighbors fillable next pass), assigning each the most common
 * already-assigned neighbor biome that's still legal there. Any cell with
 * no legal neighbor option (isolated, or none of its neighbors are
 * assigned at all) gets the universal filler — always legal by
 * construction.
 */
final class GapFiller {

    private GapFiller() {
    }

    static void fillGaps(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph) {
        List<MacroCellPos> remaining = regionCellSet.stream()
            .filter(cell -> !assignment.containsKey(cell))
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .collect(Collectors.toCollection(ArrayList::new));

        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            for (var iterator = remaining.iterator(); iterator.hasNext(); ) {
                MacroCellPos cell = iterator.next();
                Optional<BiomeId> choice = mostCommonLegalNeighborBiome(cell, assignment, graph);
                if (choice.isPresent()) {
                    assignment.put(cell, choice.get());
                    iterator.remove();
                    progress = true;
                }
            }
        }

        remaining.forEach(cell -> assignment.put(cell, graph.firstUniversalFiller()));
    }

    private static Optional<BiomeId> mostCommonLegalNeighborBiome(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        Map<BiomeId, Long> neighborBiomeCounts = cell.orthogonalNeighbors().stream()
            .map(assignment::get)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(biome -> biome, Collectors.counting()));

        return neighborBiomeCounts.entrySet().stream()
            .filter(entry -> GrowthEngine.isLegal(cell, entry.getKey(), assignment, graph))
            .max(Comparator.<Map.Entry<BiomeId, Long>>comparingLong(Map.Entry::getValue).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey);
    }
}
