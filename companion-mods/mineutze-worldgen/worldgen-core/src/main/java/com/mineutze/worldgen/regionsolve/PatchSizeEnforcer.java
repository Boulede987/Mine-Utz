package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adjacency legality alone doesn't guarantee a biome's own declared size
 * range. In practice this shouldn't currently trigger: {@link GrowthEngine}
 * only commits a patch once it already meets minCells, and
 * {@link GapFiller} only ever extends an existing same-biome component (a
 * gap cell's chosen biome always comes from an already-assigned neighbor,
 * so it fuses onto that neighbor's component rather than starting a
 * disconnected one) — so no code path today produces an undersized
 * non-filler patch. Kept as a safety net against that invariant breaking
 * later (e.g. a future weighted candidate selection or gap-fill change);
 * package-visible so it can be exercised directly in tests rather than
 * only through the (currently unreachable) integration path.
 * Universal-filler patches are exempt — they're already the fallback of
 * last resort, nothing to merge them into.
 */
final class PatchSizeEnforcer {

    private PatchSizeEnforcer() {
    }

    static void enforceMinimumPatchSize(
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        List<Set<MacroCellPos>> components = connectedComponents(assignment, regionCellSet);
        Map<MacroCellPos, Integer> patchSizeByCell = new HashMap<>();
        for (Set<MacroCellPos> component : components) {
            component.forEach(cell -> patchSizeByCell.put(cell, component.size()));
        }

        for (Set<MacroCellPos> component : components) {
            BiomeId biome = assignment.get(component.iterator().next());
            BiomeNode node = graph.node(biome);
            int minCells = grid.cellCountForWidthBlocks(node.sizeRange().minWidthBlocks());
            if (node.universalFiller() || component.size() >= minCells) {
                continue;
            }

            BiomeId replacement = bestMergeTarget(component, biome, assignment, patchSizeByCell, graph)
                .orElseGet(graph::firstUniversalFiller);
            component.forEach(cell -> assignment.put(cell, replacement));
        }
    }

    private static List<Set<MacroCellPos>> connectedComponents(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet) {
        List<MacroCellPos> orderedCells = regionCellSet.stream()
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();
        Set<MacroCellPos> visited = new HashSet<>();
        List<Set<MacroCellPos>> components = new ArrayList<>();

        for (MacroCellPos start : orderedCells) {
            if (visited.contains(start)) {
                continue;
            }
            BiomeId biome = assignment.get(start);
            Set<MacroCellPos> component = new LinkedHashSet<>();
            Deque<MacroCellPos> frontier = new ArrayDeque<>(List.of(start));
            visited.add(start);
            while (!frontier.isEmpty()) {
                MacroCellPos cell = frontier.pop();
                component.add(cell);
                for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                    if (regionCellSet.contains(neighbor) && !visited.contains(neighbor) && biome.equals(assignment.get(neighbor))) {
                        visited.add(neighbor);
                        frontier.push(neighbor);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    /** Largest (by existing patch size) neighboring biome that stays legal across every cell in the component if adopted. */
    private static Optional<BiomeId> bestMergeTarget(
        Set<MacroCellPos> component, BiomeId ownBiome, Map<MacroCellPos, BiomeId> assignment,
        Map<MacroCellPos, Integer> patchSizeByCell, BiomeGraph graph
    ) {
        Map<BiomeId, Integer> candidatePatchSize = new HashMap<>();
        for (MacroCellPos cell : component) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (component.contains(neighbor)) {
                    continue;
                }
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !neighborBiome.equals(ownBiome)) {
                    candidatePatchSize.merge(neighborBiome, patchSizeByCell.getOrDefault(neighbor, 0), Math::max);
                }
            }
        }

        return candidatePatchSize.keySet().stream()
            .filter(candidate -> isLegalForWholeComponent(component, candidate, assignment, graph))
            .max(Comparator.<BiomeId>comparingInt(candidatePatchSize::get).thenComparing(Comparator.naturalOrder()));
    }

    /** Like GrowthEngine.isLegal, but checked across every cell of a whole component at once, ignoring the component's own internal edges. */
    private static boolean isLegalForWholeComponent(
        Set<MacroCellPos> component, BiomeId candidate, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph
    ) {
        for (MacroCellPos cell : component) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (component.contains(neighbor)) {
                    continue;
                }
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                    return false;
                }
            }
        }
        return true;
    }
}
