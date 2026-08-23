package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Same-biome flood-fill grouping, shared by every pass that reasons about
 * whole patches rather than individual cells.
 *
 * <p>{@code ownCellSet} bounds only which cells can SEED a component search
 * — every returned component includes at least one cell from it. Traversal
 * itself follows same-biome connectivity through the WHOLE {@code
 * assignment} map, including any read-only context cells beyond {@code
 * ownCellSet} (e.g. {@code fixedBoundaryCells}) — a patch that continues
 * into already-solved neighbor territory must be measured at its true size,
 * not truncated at the region edge, or it reads as spuriously undersized to
 * a caller like {@link PatchSizeEnforcer}. Callers that mutate a component
 * afterward are responsible for only ever writing back to cells actually in
 * {@code ownCellSet} — this class only measures, it never assumes what's
 * safe to change.
 */
public final class ConnectedComponents {

    private ConnectedComponents() {
    }

    public static List<Set<MacroCellPos>> find(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> ownCellSet) {
        List<MacroCellPos> orderedCells = ownCellSet.stream()
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
                    if (assignment.containsKey(neighbor) && !visited.contains(neighbor) && biome.equals(assignment.get(neighbor))) {
                        visited.add(neighbor);
                        frontier.push(neighbor);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }
}
