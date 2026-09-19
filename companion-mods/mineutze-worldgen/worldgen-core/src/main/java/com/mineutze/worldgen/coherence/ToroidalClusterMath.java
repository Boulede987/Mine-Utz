package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.HexGeometry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * BFS over contiguous same-biome cells on a {@link ToroidalGrid} - "self-adjacency as
 * clustering," the SAME real mechanism already proven out in {@code
 * reachability.ClusterAreaMath} (real, independent reimplementation here, not a cross-
 * package dependency - see this class's own package's independence doc). A biome's declared
 * total size is reached by clustering multiple contiguous same-biome cells together, each
 * cell a small fixed unit - never one cell needing to hit an exact width, unlike {@link
 * PeriodicRowLayout}'s row substrate.
 */
final class ToroidalClusterMath {

    private ToroidalClusterMath() {
    }

    static Set<ToroidalPos> clusterOf(ToroidalGrid grid, ToroidalPos start, BiomeId biome, Map<ToroidalPos, BiomeId> assignment) {
        Set<ToroidalPos> visited = new LinkedHashSet<>();
        Deque<ToroidalPos> frontier = new ArrayDeque<>();
        visited.add(start);
        frontier.add(start);
        while (!frontier.isEmpty()) {
            ToroidalPos pos = frontier.poll();
            for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                if (visited.contains(neighbor)) {
                    continue;
                }
                if (biome.equals(assignment.get(neighbor))) {
                    visited.add(neighbor);
                    frontier.add(neighbor);
                }
            }
        }
        return visited;
    }

    /** Real conversion, same as {@code reachability.ClusterAreaMath.clusterWidthBlocks} - width grows with the SQUARE ROOT of cell count (area is additive, width is not). */
    static double clusterWidthBlocks(int cellCount, double baselineFaceAreaBlocks2) {
        double totalAreaBlocks2 = cellCount * baselineFaceAreaBlocks2;
        return HexGeometry.widthForCircumradius(HexGeometry.circumradiusForArea(totalAreaBlocks2));
    }
}
