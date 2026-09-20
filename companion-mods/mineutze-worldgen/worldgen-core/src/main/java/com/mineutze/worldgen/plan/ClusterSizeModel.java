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
 *
 * <p>A biome may also REQUIRE a border: every cluster of it must share at least N cell-edges with
 * biome X (a beach with the ocean). The count is of edges, so a beach cannot satisfy it by touching
 * the ocean at one corner. The requirement is one-way: X asks nothing back. A requirement that no
 * cluster could ever meet is rejected outright, like an empty size range.
 */
final class ClusterSizeModel {

    private static final double ROUNDING_TOLERANCE = 1e-9;
    /** A straight line of n hexagons has 4n + 2 outline edges: the most any cluster of n cells can have. */
    private static final int STRAIGHT_LINE_OUTLINE_PER_CELL = 4;
    private static final int STRAIGHT_LINE_OUTLINE_EXTRA = 2;

    private final List<BiomeId> biomes;
    private final Map<BiomeId, Integer> indexOf = new HashMap<>();
    private final int[] minCells;
    private final int[] maxCells;
    private final int[][] requiredTargets;
    private final int[][] requiredMinEdges;
    private final ShapeRule shape;

    ClusterSizeModel(RawRuleTable table, double atomWidthBlocks, ShapeRule shape) {
        if (atomWidthBlocks <= 0) {
            throw new IllegalArgumentException("ClusterSizeModel: atomWidthBlocks must be positive, got " + atomWidthBlocks);
        }
        this.biomes = table.allBiomeIds();
        this.shape = shape;
        this.minCells = new int[biomes.size()];
        this.maxCells = new int[biomes.size()];
        this.requiredTargets = new int[biomes.size()][];
        this.requiredMinEdges = new int[biomes.size()][];
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
        for (int index = 0; index < biomes.size(); index++) {
            readRequirements(table, index);
        }
    }

    private void readRequirements(RawRuleTable table, int biomeIndex) {
        BiomeId biome = biomes.get(biomeIndex);
        Map<BiomeId, Integer> requirements = table.requiredNeighborsOf(biome);
        for (BiomeId required : requirements.keySet()) {
            if (!indexOf.containsKey(required)) {
                throw new IllegalArgumentException("ClusterSizeModel: " + biome + " requires a border with " + required + ", which is not in the catalog");
            }
        }
        requiredTargets[biomeIndex] = new int[requirements.size()];
        requiredMinEdges[biomeIndex] = new int[requirements.size()];
        int slot = 0;
        for (BiomeId required : biomes) {
            Integer minEdges = requirements.get(required);
            if (minEdges != null) {
                requiredTargets[biomeIndex][slot] = indexOf.get(required);
                requiredMinEdges[biomeIndex][slot] = requireMeetable(table, biome, required, minEdges, maxCells[biomeIndex]);
                slot++;
            }
        }
    }

    /** Returns {@code minEdges} if some cluster of {@code biome} could share that many edges with {@code required}, otherwise throws saying why not. */
    private int requireMeetable(RawRuleTable table, BiomeId biome, BiomeId required, int minEdges, int biomeMaxCells) {
        if (biome.equals(required)) {
            throw new IllegalArgumentException("ClusterSizeModel: " + biome + " requires a border with itself");
        }
        if (minEdges < 1) {
            throw new IllegalArgumentException("ClusterSizeModel: " + biome + " requires " + minEdges + " shared edges with " + required + " - at least 1 is needed for a requirement to mean anything");
        }
        if (!table.isLegal(biome, required)) {
            throw new IllegalArgumentException("ClusterSizeModel: " + biome + " requires a border with " + required + ", which is not a legal neighbor of it - the requirement could never be met");
        }
        int largestOutline = Math.min(STRAIGHT_LINE_OUTLINE_PER_CELL * biomeMaxCells + STRAIGHT_LINE_OUTLINE_EXTRA, shape.allowedPerimeter(biomeMaxCells));
        if (minEdges > largestOutline) {
            throw new IllegalArgumentException(
                "ClusterSizeModel: " + biome + " requires " + minEdges + " shared edges with " + required + " but a cluster of at most " + biomeMaxCells
                    + " cells has at most " + largestOutline + " outline edges under the shape rule - the requirement could never be met"
            );
        }
        return minEdges;
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

    /**
     * How far a cluster is from valid: one point per cell outside its biome's range, one per thin cell (a cell not part of the solid shape its size requires), one per perimeter edge beyond
     * what the shape rule allows and one per shared edge the cluster is short of its biome's required borders ({@code borderShortfall}). Zero means valid.
     */
    int violation(BiomeId biome, int cellCount, int thinCells, int perimeter, int borderShortfall) {
        return violation(indexOf.get(biome), cellCount, thinCells, perimeter, borderShortfall);
    }

    /** As {@link #violation(BiomeId, int, int, int, int)}, by the biome's position in the rule table's biome list - what the search's hot loop uses. */
    int violation(int biomeIndex, int cellCount, int thinCells, int perimeter, int borderShortfall) {
        int outsideRange = Math.max(0, minCells[biomeIndex] - cellCount) + Math.max(0, cellCount - maxCells[biomeIndex]);
        int beyondPerimeterLimit = Math.max(0, perimeter - shape.allowedPerimeter(cellCount));
        return outsideRange + thinCells + beyondPerimeterLimit + borderShortfall;
    }

    boolean hasRequirements(int biomeIndex) {
        return requiredTargets[biomeIndex].length > 0;
    }

    /** Positions (in the biome list) of the biomes {@code biomeIndex} must share a border with; pairs up with {@link #requiredMinEdges}. */
    int[] requiredTargets(int biomeIndex) {
        return requiredTargets[biomeIndex];
    }

    /** For each of {@link #requiredTargets}, the fewest cell-edges a cluster of the biome must share with it. */
    int[] requiredMinEdges(int biomeIndex) {
        return requiredMinEdges[biomeIndex];
    }

    /** Position of {@code biome} in the rule table's biome list. */
    int biomeIndex(BiomeId biome) {
        return indexOf.get(biome);
    }

    int minCells(int biomeIndex) {
        return minCells[biomeIndex];
    }

    int maxCells(int biomeIndex) {
        return maxCells[biomeIndex];
    }
}
