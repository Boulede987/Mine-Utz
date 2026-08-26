package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

final class TestBiomeNodes {

    static final String NAMESPACE = "test";

    private TestBiomeNodes() {
    }

    static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeNode(id(path), new BiomeSizeRange(minWidthBlocks, maxWidthBlocks));
    }
}
