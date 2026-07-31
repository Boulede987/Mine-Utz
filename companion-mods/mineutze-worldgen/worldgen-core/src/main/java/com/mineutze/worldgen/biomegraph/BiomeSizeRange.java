package com.mineutze.worldgen.biomegraph;

public record BiomeSizeRange(int minWidthBlocks, int maxWidthBlocks) {

    public BiomeSizeRange {
        if (minWidthBlocks <= 0) {
            throw new IllegalArgumentException(
                "minWidthBlocks must be positive, got " + minWidthBlocks);
        }
        if (maxWidthBlocks < minWidthBlocks) {
            throw new IllegalArgumentException(
                "maxWidthBlocks (" + maxWidthBlocks + ") must be >= minWidthBlocks (" + minWidthBlocks + ")");
        }
    }
}
