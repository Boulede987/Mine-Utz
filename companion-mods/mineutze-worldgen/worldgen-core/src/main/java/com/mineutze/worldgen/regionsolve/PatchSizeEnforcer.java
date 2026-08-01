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
 * range — gap-fill's majority-vote can leave an undersized patch of any
 * biome, filler included. Two genuinely different situations produce that:
 * <ul>
 *   <li>The patch has a legal neighbor to merge into — it wasn't actually
 *       needed there, so absorb it and move on.
 *   <li>No neighbor can legally absorb it — which means it's bridging two
 *       biomes that aren't allowed to touch each other (a "required
 *       separator": removing it would put them in direct contact). It
 *       still has to respect its own size range, so {@link SeparatorWidener}
 *       grows it into its flanking patches instead of merging it away —
 *       DESIGN.md's adjacency prohibitions were never meant to produce a
 *       1-cell token seam standing in for a real buffer.
 * </ul>
 * Universal-filler patches are NOT exempt from either path — filler is a
 * normal, legitimately-placeable biome with its own size range, and an
 * undersized filler separator is exactly as visible a workaround as an
 * undersized anything-else separator.
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
            if (component.size() >= minCells) {
                continue;
            }

            Optional<BiomeId> mergeTarget = bestMergeTarget(component, biome, assignment, patchSizeByCell, graph);
            if (mergeTarget.isPresent()) {
                component.forEach(cell -> assignment.put(cell, mergeTarget.get()));
            } else {
                SeparatorWidener.widen(component, biome, minCells, assignment, regionCellSet, graph);
            }
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
