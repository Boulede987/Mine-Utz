package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;

final class TestBiomeNodes {

    static final String NAMESPACE = "test";

    private TestBiomeNodes() {
    }

    static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks) {
        return node(path, minWidthBlocks, maxWidthBlocks, GrowthCohesionProfile.UNRESTRICTED);
    }

    static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks, GrowthCohesionProfile cohesionProfile) {
        return new BiomeNode(
            id(path),
            new BiomeSizeRange(minWidthBlocks, maxWidthBlocks),
            new BiomeShapeHints(0.5, 0.5, 0.5),
            cohesionProfile
        );
    }
}
