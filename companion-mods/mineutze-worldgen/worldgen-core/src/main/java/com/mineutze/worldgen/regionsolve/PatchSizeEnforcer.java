package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.Comparator;
import java.util.HashMap;
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
 *
 * <p>Public so the export CLI's own whole-swath cleanup ({@code
 * RegionGridSolver}) can also call this directly, over the FULL combined
 * export rather than one region's own cells — a per-region call can miss an
 * undersized patch entirely if its true bordering neighbors belong to
 * regions that hadn't solved yet (found via direct user report: an 8-cell
 * ocean patch, entirely surrounded by 3 different not-yet-solved neighbor
 * regions at solve time, against ocean's real 600-1000 block/~88-cell
 * minimum — the exact same order-dependent blind spot {@link
 * PixelFragmentRepair}'s own doc describes for illegal adjacency and small
 * fragments, just for patch SIZE instead).
 */
public final class PatchSizeEnforcer {

    private PatchSizeEnforcer() {
    }

    public static void enforceMinimumPatchSize(
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, regionCellSet);
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
                SeparatorWidener.widen(component, biome, minCells, assignment, regionCellSet, graph, Set.of());
            }
        }
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
