package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Direct small-fixture tests - flagged by this session's test-coverage audit as having
 * zero dedicated tests despite carrying a real production-crash-preventing derivation
 * (see {@code maxTrianglesPerBiome}'s own doc).
 */
class FaceFairnessQuotaTest {

    private static BiomeId id(String path) {
        return new BiomeId("test", path);
    }

    private static BiomeNode node(String path, int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeNode(id(path), new BiomeSizeRange(minWidthBlocks, maxWidthBlocks));
    }

    @Test
    void quotaIsProportionalToSizeRangeMidpointSquared() {
        BiomeId small = id("small"); // midpoint 100 -> weight 10_000
        BiomeId big = id("big");     // midpoint 200 -> weight 40_000, 4x small's
        BiomeGraph graph = new BiomeGraph(List.of(node("small", 100, 100), node("big", 200, 200)), List.of(AdjacencyRule.allow(small, big, 10)));

        FaceFairnessQuota quota = FaceFairnessQuota.forRegion(List.of(small, big), graph, 50, Double.POSITIVE_INFINITY);

        assertEquals(10.0, quota.deficit(small), 1e-9, "small's fair share of 50 faces, weighted 10_000 of 50_000 total");
        assertEquals(40.0, quota.deficit(big), 1e-9, "big's fair share, weighted 40_000 of 50_000 total - 4x small's, matching its 4x weight");
    }

    @Test
    void incrementAndDecrementMoveTheDeficitByExactlyOne() {
        BiomeId biome = id("biome");
        BiomeGraph graph = new BiomeGraph(List.of(node("biome", 100, 100)), List.of());
        FaceFairnessQuota quota = FaceFairnessQuota.forRegion(List.of(biome), graph, 10, Double.POSITIVE_INFINITY);
        double initialDeficit = quota.deficit(biome);

        quota.increment(biome);
        assertEquals(initialDeficit - 1, quota.deficit(biome), 1e-9);

        quota.decrement(biome);
        assertEquals(initialDeficit, quota.deficit(biome), 1e-9);
    }

    @Test
    void deficitIsNegativeInfinityOnceTheSoftCapIsReached() {
        BiomeId biome = id("biome");
        BiomeGraph graph = new BiomeGraph(List.of(node("biome", 100, 100)), List.of());
        // weight = 100^2 = 10_000; cap = ceil(PATCH_COUNT_SLACK(4.0) * regionAreaBlocks2 / totalWeight)
        // = ceil(4.0 * 2500 / 10_000) = ceil(1.0) = 1.
        FaceFairnessQuota quota = FaceFairnessQuota.forRegion(List.of(biome), graph, 10, 2500.0);

        quota.increment(biome);

        assertEquals(Double.NEGATIVE_INFINITY, quota.deficit(biome), "at the cap, deficit must sort dead last among candidates - never override legality, only reorder ties");
    }

    @Test
    void anInfiniteRegionAreaNeverCaps() {
        BiomeId biome = id("biome");
        BiomeGraph graph = new BiomeGraph(List.of(node("biome", 100, 100)), List.of());
        FaceFairnessQuota quota = FaceFairnessQuota.forRegion(List.of(biome), graph, 10, Double.POSITIVE_INFINITY);

        for (int i = 0; i < 1000; i++) {
            quota.increment(biome);
        }

        assertNotEquals(Double.NEGATIVE_INFINITY, quota.deficit(biome), "the simpler no-cap overload (POSITIVE_INFINITY) must never soft-cap a biome");
    }
}
