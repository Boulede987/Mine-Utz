package com.mineutze.worldgen.regionsolve.pixelgrid;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

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
import java.util.function.Function;

/**
 * Export-only today, deliberately kept - real callers ({@link PatchSizeEnforcer}, {@link
 * RegionIntegrityValidator}) only run via the standalone visual/JSON export tooling,
 * never the shipped mod's live path.
 *
 * <p>Same-biome flood-fill grouping, shared by every pass reasoning about whole patches
 * rather than individual cells.
 *
 * <p>{@code ownCellSet} bounds only which cells can SEED a component search - every
 * returned component includes at least one cell from it. Traversal follows same-biome
 * connectivity through the WHOLE {@code assignment} map, including any read-only context
 * cells beyond {@code ownCellSet} (e.g. {@code fixedBoundaryCells}) - a patch continuing
 * into already-solved neighbor territory must be measured at its true size, not
 * truncated at the region edge, or it reads as spuriously undersized to a caller like
 * {@link PatchSizeEnforcer}. Callers that mutate a component afterward are responsible
 * for only writing back to cells actually in {@code ownCellSet} - this class only
 * measures, never assumes what's safe to change.
 */
public final class ConnectedComponents {

    private ConnectedComponents() {
    }

    public static List<Set<MacroCellPos>> find(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> ownCellSet) {
        return find(assignment, ownCellSet, MacroCellPos::orthogonalNeighbors);
    }

    /**
     * Same as {@link #find(Map, Set)}, but connectivity follows {@code neighborFn} instead
     * of always orthogonal-only - {@link RegionIntegrityValidator} uses this with Moore
     * (8-way) neighbors for its own small-fragment check, since the growth algorithm
     * treats diagonal adjacency as real connectivity everywhere (legality, cohesion, the
     * head-start reservation pass), so measuring "isolated" via orthogonal-only adjacency
     * split a real diagonally-touching pair into two false single-cell fragments.
     */
    public static List<Set<MacroCellPos>> find(
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> ownCellSet, Function<MacroCellPos, List<MacroCellPos>> neighborFn
    ) {
        Set<MacroCellPos> visited = new HashSet<>();
        List<Set<MacroCellPos>> components = new ArrayList<>();

        for (MacroCellPos seed : orderedSeeds(ownCellSet)) {
            if (visited.contains(seed)) {
                continue;
            }
            components.add(floodFillFrom(seed, assignment, neighborFn, visited));
        }
        return components;
    }

    /** Deterministic seed order so which cell "starts" a component (and the components list's own order) never depends on {@code ownCellSet}'s own (hash-based, unordered) iteration order. */
    private static List<MacroCellPos> orderedSeeds(Set<MacroCellPos> ownCellSet) {
        return ownCellSet.stream()
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();
    }

    /** Every same-biome cell reachable from {@code seed} via {@code neighborFn} - marks each visited along the way, so no cell is ever claimed by two components. */
    private static Set<MacroCellPos> floodFillFrom(
        MacroCellPos seed, Map<MacroCellPos, BiomeId> assignment, Function<MacroCellPos, List<MacroCellPos>> neighborFn, Set<MacroCellPos> visited
    ) {
        BiomeId biome = assignment.get(seed);
        Set<MacroCellPos> component = new LinkedHashSet<>();
        Deque<MacroCellPos> frontier = new ArrayDeque<>(List.of(seed));
        visited.add(seed);
        while (!frontier.isEmpty()) {
            MacroCellPos cell = frontier.pop();
            component.add(cell);
            for (MacroCellPos neighbor : neighborFn.apply(cell)) {
                if (assignment.containsKey(neighbor) && !visited.contains(neighbor) && biome.equals(assignment.get(neighbor))) {
                    visited.add(neighbor);
                    frontier.push(neighbor);
                }
            }
        }
        return component;
    }
}
