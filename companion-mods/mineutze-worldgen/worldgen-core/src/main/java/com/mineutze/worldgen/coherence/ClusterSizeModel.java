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
 * <p><b>Roundness</b> ("a biome must be roughly round", user requirement): a cluster's
 * perimeter - its cell-edges that touch a different biome - may not exceed {@code
 * roundnessRatio} times the smallest perimeter any cluster of that many hexagons can have,
 * {@code 2 * ceil(sqrt(12n - 3))} (Harary and Harborth, hexagonal animals). A ratio of 1
 * demands the most compact shape possible; larger values tolerate more ragged ones. The number
 * is a design choice, not a measured constant - "roughly round" has no exact definition.
 */
final class ClusterSizeModel {

    private static final double ROUNDING_TOLERANCE = 1e-9;

    private final List<BiomeId> biomes;
    private final Map<BiomeId, Integer> indexOf = new HashMap<>();
    private final int[] minCells;
    private final int[] maxCells;
    private final double roundnessRatio;

    ClusterSizeModel(RawRuleTable table, double atomWidthBlocks, double roundnessRatio) {
        if (atomWidthBlocks <= 0) {
            throw new IllegalArgumentException("ClusterSizeModel: atomWidthBlocks must be positive, got " + atomWidthBlocks);
        }
        if (roundnessRatio < 1.0) {
            throw new IllegalArgumentException("ClusterSizeModel: roundnessRatio must be at least 1 (no shape is more compact than the minimum), got " + roundnessRatio);
        }
        this.biomes = table.allBiomeIds();
        this.roundnessRatio = roundnessRatio;
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

    /** Largest perimeter, in cell-edges, a cluster of {@code cellCount} cells may have and still count as round. */
    int allowedPerimeter(int cellCount) {
        return (int) Math.ceil(roundnessRatio * minimumPerimeter(cellCount) - ROUNDING_TOLERANCE);
    }

    /** Smallest possible perimeter of a hexagon cluster of {@code cellCount} cells: 6, 10, 12, ... 18 for the seven-cell flower. */
    static int minimumPerimeter(int cellCount) {
        int target = 12 * cellCount - 3;
        int root = (int) Math.ceil(Math.sqrt(target));
        while (root * root < target) {
            root++;
        }
        while ((root - 1) * (root - 1) >= target) {
            root--;
        }
        return 2 * root;
    }

    /** How far a cluster is from valid: one point per cell outside its biome's range plus one per perimeter edge beyond what roundness allows. Zero means valid. */
    int violation(BiomeId biome, int cellCount, int perimeter) {
        return violation(indexOf.get(biome), cellCount, perimeter);
    }

    /** As {@link #violation(BiomeId, int, int)}, by the biome's position in the rule table's biome list - what the search's hot loop uses. */
    int violation(int biomeIndex, int cellCount, int perimeter) {
        int outsideRange = Math.max(0, minCells[biomeIndex] - cellCount) + Math.max(0, cellCount - maxCells[biomeIndex]);
        int beyondRoundness = Math.max(0, perimeter - allowedPerimeter(cellCount));
        return outsideRange + beyondRoundness;
    }

    int minCells(int biomeIndex) {
        return minCells[biomeIndex];
    }

    int maxCells(int biomeIndex) {
        return maxCells[biomeIndex];
    }
}
