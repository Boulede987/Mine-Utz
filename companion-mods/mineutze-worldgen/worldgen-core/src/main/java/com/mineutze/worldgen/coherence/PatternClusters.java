package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;

/**
 * The connected same-biome regions of a cell pattern, numbered (2026-09-20). Cell
 * {@code (q, r)} has index {@code r * width + q}. These are the biomes as the search sees them,
 * and what a region of the rendered map is compared against.
 */
final class PatternClusters {

    private final int[] clusterOfCell;
    private final int[] cellsInCluster;

    private PatternClusters(int[] clusterOfCell, int[] cellsInCluster) {
        this.clusterOfCell = clusterOfCell;
        this.cellsInCluster = cellsInCluster;
    }

    static PatternClusters of(ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern) {
        int[] clusterOfCell = new int[grid.cellCount()];
        Arrays.fill(clusterOfCell, -1);
        int[] sizes = new int[grid.cellCount()];
        int clusterCount = 0;
        for (ToroidalPos start : grid.allPositionsInFillOrder()) {
            if (clusterOfCell[indexOf(grid, start)] != -1) {
                continue;
            }
            BiomeId biome = pattern.get(start);
            Deque<ToroidalPos> frontier = new ArrayDeque<>();
            frontier.add(start);
            clusterOfCell[indexOf(grid, start)] = clusterCount;
            while (!frontier.isEmpty()) {
                ToroidalPos pos = frontier.poll();
                sizes[clusterCount]++;
                for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                    if (clusterOfCell[indexOf(grid, neighbor)] == -1 && biome.equals(pattern.get(neighbor))) {
                        clusterOfCell[indexOf(grid, neighbor)] = clusterCount;
                        frontier.add(neighbor);
                    }
                }
            }
            clusterCount++;
        }
        return new PatternClusters(clusterOfCell, Arrays.copyOf(sizes, clusterCount));
    }

    private static int indexOf(ToroidalGrid grid, ToroidalPos pos) {
        return pos.r() * grid.width() + pos.q();
    }

    int clusterCount() {
        return cellsInCluster.length;
    }

    /** Cluster number of each cell, indexed by {@code row * width + column}. */
    int[] clusterOfCell() {
        return clusterOfCell;
    }

    /** Number of cells in each cluster. */
    int[] cellsInCluster() {
        return cellsInCluster;
    }

    int totalCells() {
        return clusterOfCell.length;
    }
}
