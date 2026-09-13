package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

public final class TestBiomeNodes {

    public static final String NAMESPACE = "test";

    private TestBiomeNodes() {
    }

    public static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    public static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeNode(id(path), new BiomeSizeRange(minWidthBlocks, maxWidthBlocks));
    }
}
