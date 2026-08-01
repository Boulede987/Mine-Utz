package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * When an undersized patch can't be legally merged into any neighbor, it's
 * not leftover cleanup — it's bridging two biomes that aren't allowed to
 * touch each other, and removing it would bring them into direct contact.
 * It still has to respect its own declared size range, though: a 1-cell
 * seam standing in for "these two aren't allowed to touch" is a visible
 * workaround, not the design (DESIGN.md never asked for a token buffer,
 * and forbidding a pair was never meant to produce one).
 *
 * Widens the patch by claiming cells from whichever patches border it,
 * same legality rule as {@link GrowthEngine} — only claims a cell if this
 * biome stays legal against every OTHER neighbor that cell touches, so a
 * non-filler separator can't widen itself into a new illegal adjacency.
 * Stops at the target size or when no more legally-claimable cells exist,
 * whichever comes first — it's a best-effort widen, not a guarantee.
 */
final class SeparatorWidener {

    private SeparatorWidener() {
    }

    static void widen(
        Set<MacroCellPos> component, BiomeId ownBiome, int targetMinCells,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph
    ) {
        if (component.size() >= targetMinCells) {
            return;
        }

        Set<MacroCellPos> claimed = new LinkedHashSet<>(component);
        TreeSet<MacroCellPos> frontier = new TreeSet<>(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z));
        addUnclaimedNeighbors(component, claimed, regionCellSet, frontier);

        while (claimed.size() < targetMinCells && !frontier.isEmpty()) {
            MacroCellPos next = frontier.pollFirst();
            if (!isLegalToClaim(next, ownBiome, claimed, assignment, graph)) {
                continue;
            }
            claimed.add(next);
            addUnclaimedNeighbors(Set.of(next), claimed, regionCellSet, frontier);
        }

        for (MacroCellPos cell : claimed) {
            assignment.put(cell, ownBiome);
        }
    }

    private static void addUnclaimedNeighbors(Set<MacroCellPos> from, Set<MacroCellPos> claimed, Set<MacroCellPos> regionCellSet, TreeSet<MacroCellPos> frontier) {
        for (MacroCellPos cell : from) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (regionCellSet.contains(neighbor) && !claimed.contains(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
    }

    /** Legal to steal this cell if the candidate biome is still allowed next to every neighbor it has outside the (growing) claimed set. */
    private static boolean isLegalToClaim(MacroCellPos cell, BiomeId candidate, Set<MacroCellPos> claimed, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            if (claimed.contains(neighbor)) {
                continue;
            }
            BiomeId neighborBiome = assignment.get(neighbor);
            if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                return false;
            }
        }
        return true;
    }
}
