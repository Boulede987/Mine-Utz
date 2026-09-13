package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.HexGeometry;

import com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement;

import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

/**
 * A face's real, achievable WIDTH range, derived once from {@link
 * WeightedVertexPlacement.AreaRange} so domain filtering and width picking work in the
 * same unit {@link BiomeSizeRange} uses - see {@link TriangleIdentitySizeAssignment} for
 * why (committed-neighbor geometry can shrink what a face can hold, before identity is
 * even decided).
 */
record AchievableWidthRange(int minWidthBlocks, int maxWidthBlocks) {

    static AchievableWidthRange from(WeightedVertexPlacement.AreaRange areaRange) {
        return new AchievableWidthRange(endpointFromArea(areaRange.minAreaBlocks2()), endpointFromArea(areaRange.maxAreaBlocks2()));
    }

    /**
     * The one, narrow place this conversion computes a real hexagon area from a width -
     * confined here, not {@link FaceWidthPicker}'s geometry-agnostic width picking. Uses
     * {@link HexGeometry}, same shared conversion {@link WeightedVertexPlacement} uses,
     * not a second independently-maintained copy.
     */
    private static int endpointFromArea(double areaBlocks2) {
        double circumradius = HexGeometry.circumradiusForArea(areaBlocks2);
        return (int) Math.round(HexGeometry.widthForCircumradius(circumradius));
    }

    /** {@code true} if {@code candidateRange}'s own declared range overlaps this achievable range at all. */
    boolean fits(BiomeSizeRange candidateRange) {
        return candidateRange.maxWidthBlocks() >= minWidthBlocks && candidateRange.minWidthBlocks() <= maxWidthBlocks;
    }

    /**
     * Range width-picking draws from once a biome is chosen - intersection of its catalog
     * range and this achievable range, not raw catalog range alone. Non-empty by
     * construction: {@link #fits} already required real overlap before this biome could
     * be chosen - empty intersection here is a real bug, not something to paper over.
     */
    BiomeSizeRange intersect(BiomeSizeRange biomeRange) {
        int min = Math.max(biomeRange.minWidthBlocks(), minWidthBlocks);
        int max = Math.min(biomeRange.maxWidthBlocks(), maxWidthBlocks);
        if (min > max) {
            throw new IllegalStateException(
                "AchievableWidthRange: intersect found an empty intersection (biomeRange=" + biomeRange
                    + ", achievable=" + this + ") for an already-chosen biome - fits should have excluded this candidate, real bug"
            );
        }
        return new BiomeSizeRange(min, max);
    }
}
