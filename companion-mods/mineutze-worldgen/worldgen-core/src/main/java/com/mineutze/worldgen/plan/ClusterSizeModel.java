package com.mineutze.worldgen.plan;

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
 * <p>What shape a cluster may take (irregular yes, thin snakes no) is a {@link ShapeRule}.
 */
final class ClusterSizeModel {

    private static final double ROUNDING_TOLERANCE = 1e-9;

    private final List<BiomeId> biomes;
    private final Map<BiomeId, Integer> indexOf = new HashMap<>();
    private final int[] minCells;
    private final int[] maxCells;
    private final ShapeRule shape;

    ClusterSizeModel(RawRuleTable table, double atomWidthBlocks, ShapeRule shape) {
        if (atomWidthBlocks <= 0) {
            throw new IllegalArgumentException("ClusterSizeModel: atomWidthBlocks must be positive, got " + atomWidthBlocks);
        }
        this.biomes = table.allBiomeIds();
        this.shape = shape;
        this.minCells = new int[biomes.size()];
        this.maxCells = new int[biomes.size()];
        for (int index = 0; index < biomes.size(); index++) {
            BiomeId biome = biomes.get(index);
            RawRuleTable.SizeRange range = table.sizeRangeOf(biome);
            double minAtoms = squared(range.minWidthBlocks() / atomWidthBlocks);
            double maxAtoms = squared(range.maxWidthBlocks() / atomWidthBlocks);
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

    private static double squared(double value) {
        return value * value;
    }

    int minCells(BiomeId biome) {
        return minCells[indexOf.get(biome)];
    }

    int maxCells(BiomeId biome) {
        return maxCells[indexOf.get(biome)];
    }

    ShapeRule shape() {
        return shape;
    }

    /** How far a cluster is from valid: one point per cell outside its biome's range, one per thin cell (a cell not part of the solid shape its size requires) and one per perimeter edge beyond what the shape rule allows. Zero means valid. */
    int violation(BiomeId biome, int cellCount, int thinCells, int perimeter) {
        return violation(indexOf.get(biome), cellCount, thinCells, perimeter);
    }

    /** As {@link #violation(BiomeId, int, int, int)}, by the biome's position in the rule table's biome list - what the search's hot loop uses. */
    int violation(int biomeIndex, int cellCount, int thinCells, int perimeter) {
        int outsideRange = Math.max(0, minCells[biomeIndex] - cellCount) + Math.max(0, cellCount - maxCells[biomeIndex]);
        int beyondPerimeterLimit = Math.max(0, perimeter - shape.allowedPerimeter(cellCount));
        return outsideRange + thinCells + beyondPerimeterLimit;
    }

    int minCells(int biomeIndex) {
        return minCells[biomeIndex];
    }

    int maxCells(int biomeIndex) {
        return maxCells[biomeIndex];
    }
}
