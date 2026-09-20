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
 * <p>A biome may also REQUIRE borders (a {@link ResolvedBorderRequirement}): every cluster of it must
 * share at least N cell-edges with a group of biomes, alone or combined with "and" / "or" (a beach
 * with the ocean or the lukewarm ocean). The count is of edges, so a beach cannot satisfy it by touching
 * the ocean at one corner. The requirement is one-way: the biomes it names ask nothing back. A
 * requirement that no cluster could ever meet is rejected outright, like an empty size range.
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
    private final CompiledRequirement[] requirements;
    private final ShapeRule shape;

    ClusterSizeModel(RawRuleTable table, double atomWidthBlocks, ShapeRule shape) {
        if (atomWidthBlocks <= 0) {
            throw new IllegalArgumentException("ClusterSizeModel: atomWidthBlocks must be positive, got " + atomWidthBlocks);
        }
        this.biomes = table.allBiomeIds();
        this.shape = shape;
        this.minCells = new int[biomes.size()];
        this.maxCells = new int[biomes.size()];
        this.requirements = new CompiledRequirement[biomes.size()];
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
        table.requirementOf(biome).ifPresent(requirement -> {
            String reason = whyNeverMet(table, biome, requirement, maxCells[biomeIndex]);
            if (reason != null) {
                throw new IllegalArgumentException("ClusterSizeModel: " + biome + " requires " + requirement + ", which could never be met: " + reason);
            }
            requirements[biomeIndex] = compile(requirement);
        });
    }

    /** Null if some cluster of {@code biome} could meet {@code requirement}; otherwise why none ever could. */
    private String whyNeverMet(RawRuleTable table, BiomeId biome, ResolvedBorderRequirement requirement, int biomeMaxCells) {
        return switch (requirement) {
            case ResolvedBorderRequirement.Share share -> whyShareNeverMet(table, biome, share, biomeMaxCells);
            case ResolvedBorderRequirement.AllOf all -> all.parts().stream().map(part -> whyNeverMet(table, biome, part, biomeMaxCells)).filter(reason -> reason != null).findFirst().orElse(null);
            case ResolvedBorderRequirement.AnyOf any -> {
                List<String> reasons = new java.util.ArrayList<>();
                for (ResolvedBorderRequirement part : any.parts()) {
                    String reason = whyNeverMet(table, biome, part, biomeMaxCells);
                    if (reason == null) {
                        yield null;
                    }
                    reasons.add(reason);
                }
                yield "none of the alternatives can be met (" + String.join("; ", reasons) + ")";
            }
        };
    }

    private String whyShareNeverMet(RawRuleTable table, BiomeId biome, ResolvedBorderRequirement.Share share, int biomeMaxCells) {
        if (share.biomes().stream().noneMatch(member -> table.isLegal(biome, member))) {
            return share.biomes().isEmpty()
                ? "'" + share.subject() + "' holds no biome"
                : (share.subject().contains(":") ? share.subject() + " is not" : "none of the biomes of '" + share.subject() + "' is") + " a legal neighbor of " + biome;
        }
        int largestOutline = Math.min(STRAIGHT_LINE_OUTLINE_PER_CELL * biomeMaxCells + STRAIGHT_LINE_OUTLINE_EXTRA, shape.allowedPerimeter(biomeMaxCells));
        if (share.minEdges() > largestOutline) {
            return share.minEdges() + " shared edges with '" + share.subject() + "' are asked for, but a cluster of at most " + biomeMaxCells + " cells has at most "
                + largestOutline + " outline edges under the shape rule";
        }
        return null;
    }

    private CompiledRequirement compile(ResolvedBorderRequirement requirement) {
        return switch (requirement) {
            case ResolvedBorderRequirement.Share share -> new CompiledRequirement(
                CompiledRequirement.Kind.SHARE, share.minEdges(), share.biomes().stream().mapToInt(indexOf::get).toArray(), new CompiledRequirement[0]
            );
            case ResolvedBorderRequirement.AllOf all -> new CompiledRequirement(
                CompiledRequirement.Kind.ALL, 0, new int[0], all.parts().stream().map(this::compile).toArray(CompiledRequirement[]::new)
            );
            case ResolvedBorderRequirement.AnyOf any -> new CompiledRequirement(
                CompiledRequirement.Kind.ANY, 0, new int[0], any.parts().stream().map(this::compile).toArray(CompiledRequirement[]::new)
            );
        };
    }

    /** {@link ResolvedBorderRequirement} with biomes as positions in the biome list, evaluated against an array of shared edges per biome: what the search's hot loop uses. */
    private static final class CompiledRequirement {

        enum Kind { SHARE, ALL, ANY }

        private final Kind kind;
        private final int minEdges;
        private final int[] biomes;
        private final CompiledRequirement[] parts;

        CompiledRequirement(Kind kind, int minEdges, int[] biomes, CompiledRequirement[] parts) {
            this.kind = kind;
            this.minEdges = minEdges;
            this.biomes = biomes;
            this.parts = parts;
        }

        int shortfall(int[] sharedEdgesByBiome) {
            switch (kind) {
                case SHARE:
                    int shared = 0;
                    for (int biome : biomes) {
                        shared += sharedEdgesByBiome[biome];
                    }
                    return Math.max(0, minEdges - shared);
                case ALL:
                    int total = 0;
                    for (CompiledRequirement part : parts) {
                        total += part.shortfall(sharedEdgesByBiome);
                    }
                    return total;
                default:
                    int best = Integer.MAX_VALUE;
                    for (CompiledRequirement part : parts) {
                        best = Math.min(best, part.shortfall(sharedEdgesByBiome));
                    }
                    return best;
            }
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

    boolean hasRequirement(int biomeIndex) {
        return requirements[biomeIndex] != null;
    }

    /**
     * How many shared edges a cluster of the biome is short of its required borders, given how many
     * edges it shares with each biome (indexed like the biome list); zero when it has none required or all are met.
     */
    int borderShortfall(int biomeIndex, int[] sharedEdgesByBiome) {
        return requirements[biomeIndex] == null ? 0 : requirements[biomeIndex].shortfall(sharedEdgesByBiome);
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
