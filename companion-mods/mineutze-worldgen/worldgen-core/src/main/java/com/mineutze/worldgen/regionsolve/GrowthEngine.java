package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Grows one candidate biome outward from a seed cell by flood-fill,
 * claiming only macro-cells inside the region that are still free and
 * legal (the candidate must be allowed, per the BiomeGraph, next to every
 * already-assigned cell outside the growing patch itself).
 *
 * Does not backtrack or pick a different candidate — that's
 * {@link RegionSolver}'s job. This just answers: "can this one candidate
 * grow to at least its minimum size here, and if so, which cells does it
 * claim?"
 */
public final class GrowthEngine {

    private GrowthEngine() {
    }

    public record GrowthAttempt(boolean success, Map<MacroCellPos, BiomeId> claimedCells) {
    }

    public static GrowthAttempt attemptGrowth(
        MacroCellPos seedCell,
        BiomeId candidate,
        int minCells,
        int targetCells,
        RegionPos region,
        MacroGridConfig grid,
        BiomeGraph graph,
        Map<MacroCellPos, BiomeId> externallyAssigned
    ) {
        Set<MacroCellPos> regionCells = Set.copyOf(grid.cellsInRegion(region));

        if (externallyAssigned.containsKey(seedCell) || !isLegal(seedCell, candidate, externallyAssigned, graph)) {
            return new GrowthAttempt(false, Map.of());
        }

        Map<MacroCellPos, BiomeId> claimed = new LinkedHashMap<>();
        claimed.put(seedCell, candidate);

        TreeSet<MacroCellPos> frontier = new TreeSet<>(frontierOrder(seedCell));
        addLegalUnclaimedNeighbors(seedCell, candidate, regionCells, externallyAssigned, claimed, frontier, graph);

        while (claimed.size() < targetCells && !frontier.isEmpty()) {
            MacroCellPos next = frontier.pollFirst();
            claimed.put(next, candidate);
            addLegalUnclaimedNeighbors(next, candidate, regionCells, externallyAssigned, claimed, frontier, graph);
        }

        boolean success = claimed.size() >= minCells;
        return new GrowthAttempt(success, success ? Map.copyOf(claimed) : Map.of());
    }

    private static void addLegalUnclaimedNeighbors(
        MacroCellPos cell,
        BiomeId candidate,
        Set<MacroCellPos> regionCells,
        Map<MacroCellPos, BiomeId> externallyAssigned,
        Map<MacroCellPos, BiomeId> claimed,
        TreeSet<MacroCellPos> frontier,
        BiomeGraph graph
    ) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            if (regionCells.contains(neighbor)
                && !claimed.containsKey(neighbor)
                && !externallyAssigned.containsKey(neighbor)
                && isLegal(neighbor, candidate, externallyAssigned, graph)) {
                frontier.add(neighbor);
            }
        }
    }

    /**
     * Candidate is legal at this cell if it's allowed next to every
     * already-assigned neighbor outside the growing patch. Package-visible:
     * {@link RegionSolver}'s gap-fill pass reuses this exact check.
     */
    static boolean isLegal(
        MacroCellPos cell,
        BiomeId candidate,
        Map<MacroCellPos, BiomeId> externallyAssigned,
        BiomeGraph graph
    ) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            BiomeId neighborBiome = externallyAssigned.get(neighbor);
            if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                return false;
            }
        }
        return true;
    }

    private static Comparator<MacroCellPos> frontierOrder(MacroCellPos seedCell) {
        return Comparator
            .comparingLong((MacroCellPos cell) -> cell.distanceSquaredTo(seedCell))
            .thenComparingInt(MacroCellPos::x)
            .thenComparingInt(MacroCellPos::z);
    }
}
