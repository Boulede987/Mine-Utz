package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a valid cluster of same-biome cells looks like when the grid's cells are small "atoms"
 * of a fixed area (2026-09-19). A biome's size range is an AREA, written as the width of a
 * regular hexagon with that area (see {@code BiomeSizeRange}), so a biome of size-width
 * {@code w} covers {@code (w / atomWidth)^2} atoms - area grows with the square of the width.
 * A biome may not cover a fraction of an atom, so its minimum rounds UP and its maximum rounds
 * DOWN; a range that contains no whole atom count is rejected outright, because no cluster
 * could ever satisfy it at this atom size.
 *
 * <p><b>Shape</b>: biomes may be irregular - natural, not round - but not thin snakes (user,
 * 2026-09-19; replaced an earlier perimeter-based roundness rule). "Thin" is judged by whether a
 * part of the cluster is thick, and how thick scales with the cluster (see {@link Thickness}):
 * a large cluster must be built from flower-shaped cores (a cell plus its six neighbors, three
 * cells across), and every cell must be within {@code tailReachCells} steps of one, so bumps and
 * short necks are allowed but a long thin arm is not.
 */
final class ClusterSizeModel {

    /** The kind of solid piece every cell of a cluster must belong to, chosen by the cluster's size. */
    enum Thickness {
        /** One or two cells: nothing to check. */
        NONE,
        /** Three to six cells: every cell must be part of a triangle of three mutually adjacent cluster cells. */
        TRIANGLE,
        /** Seven or more cells: the cluster must contain flowers, and every cell must be within the tail reach of a flower's center. */
        FLOWER
    }

    private static final int SMALLEST_CLUSTER_NEEDING_A_TRIANGLE = 3;
    private static final int SMALLEST_CLUSTER_ABLE_TO_HOLD_A_FLOWER = 7;
    private static final double ROUNDING_TOLERANCE = 1e-9;

    private final List<BiomeId> biomes;
    private final Map<BiomeId, Integer> indexOf = new HashMap<>();
    private final int[] minCells;
    private final int[] maxCells;
    private final int tailReachCells;
    private final int smallestClusterNeedingAFlower;

    /** {@code smallestClusterNeedingAFlower} is the cell count from which a cluster must contain flowers; below it (down to three cells) only triangles are required. */
    ClusterSizeModel(RawRuleTable table, double atomWidthBlocks, int tailReachCells, int smallestClusterNeedingAFlower) {
        if (atomWidthBlocks <= 0) {
            throw new IllegalArgumentException("ClusterSizeModel: atomWidthBlocks must be positive, got " + atomWidthBlocks);
        }
        if (tailReachCells < 1) {
            throw new IllegalArgumentException("ClusterSizeModel: tailReachCells must be at least 1 (a flower's own rim is one step from its center), got " + tailReachCells);
        }
        if (smallestClusterNeedingAFlower < SMALLEST_CLUSTER_ABLE_TO_HOLD_A_FLOWER) {
            throw new IllegalArgumentException(
                "ClusterSizeModel: smallestClusterNeedingAFlower must be at least " + SMALLEST_CLUSTER_ABLE_TO_HOLD_A_FLOWER + " (a smaller cluster cannot hold a flower), got " + smallestClusterNeedingAFlower
            );
        }
        this.biomes = table.allBiomeIds();
        this.tailReachCells = tailReachCells;
        this.smallestClusterNeedingAFlower = smallestClusterNeedingAFlower;
        this.minCells = new int[biomes.size()];
        this.maxCells = new int[biomes.size()];
        for (int index = 0; index < biomes.size(); index++) {
            BiomeId biome = biomes.get(index);
            RawRuleTable.SizeRange range = table.sizeRangeOf(biome);
            double minAtoms = Math.pow(range.minWidthBlocks() / atomWidthBlocks, 2);
            double maxAtoms = Math.pow(range.maxWidthBlocks() / atomWidthBlocks, 2);
            minCells[index] = (int) Math.ceil(minAtoms - ROUNDING_TOLERANCE);
            maxCells[index] = (int) Math.floor(maxAtoms + ROUNDING_TOLERANCE);
            if (minCells[index] > maxCells[index]) {
                throw new IllegalArgumentException(
                    "ClusterSizeModel: " + biome + " size range [" + range.minWidthBlocks() + "," + range.maxWidthBlocks() + "] contains no whole number of "
                        + atomWidthBlocks + "-block atoms (needs at least " + minCells[index] + " but at most " + maxCells[index] + ") - use a smaller atom"
                );
            }
            indexOf.put(biome, index);
        }
    }

    int minCells(BiomeId biome) {
        return minCells[indexOf.get(biome)];
    }

    int maxCells(BiomeId biome) {
        return maxCells[indexOf.get(biome)];
    }

    int tailReachCells() {
        return tailReachCells;
    }

    Thickness thicknessRequiredFor(int cellCount) {
        if (cellCount >= smallestClusterNeedingAFlower) {
            return Thickness.FLOWER;
        }
        return cellCount >= SMALLEST_CLUSTER_NEEDING_A_TRIANGLE ? Thickness.TRIANGLE : Thickness.NONE;
    }

    /** How far a cluster is from valid: one point per cell outside its biome's range plus one per thin cell (a cell not part of the solid shape its size requires). Zero means valid. */
    int violation(BiomeId biome, int cellCount, int thinCells) {
        return violation(indexOf.get(biome), cellCount, thinCells);
    }

    /** As {@link #violation(BiomeId, int, int)}, by the biome's position in the rule table's biome list - what the search's hot loop uses. */
    int violation(int biomeIndex, int cellCount, int thinCells) {
        int outsideRange = Math.max(0, minCells[biomeIndex] - cellCount) + Math.max(0, cellCount - maxCells[biomeIndex]);
        return outsideRange + thinCells;
    }

    int minCells(int biomeIndex) {
        return minCells[biomeIndex];
    }

    int maxCells(int biomeIndex) {
        return maxCells[biomeIndex];
    }
}
