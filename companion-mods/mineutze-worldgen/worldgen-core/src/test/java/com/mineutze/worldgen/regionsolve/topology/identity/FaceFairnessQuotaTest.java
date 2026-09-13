package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.biomegraph.BiomeId;
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

    /**
     * "Mostly equally rare" (see {@link FaceFairnessQuota#fairShareWeights}'s own doc for
     * why size-range-based weighting was removed) - a narrow-range and a wide-range biome
     * get the exact SAME fair share, regardless of their own {@code BiomeSizeRange}.
     */
    @Test
    void quotaIsUniformAcrossBiomesRegardlessOfSizeRange() {
        BiomeId small = id("small");
        BiomeId big = id("big");

        FaceFairnessQuota quota = FaceFairnessQuota.forRegion(List.of(small, big), 50, Double.POSITIVE_INFINITY);

        assertEquals(25.0, quota.deficit(small), 1e-9, "small's fair share of 50 faces, split evenly with big");
        assertEquals(25.0, quota.deficit(big), 1e-9, "big's fair share - same as small's, size range no longer weights this");
    }

    @Test
    void incrementAndDecrementMoveTheDeficitByExactlyOne() {
        BiomeId biome = id("biome");
        FaceFairnessQuota quota = FaceFairnessQuota.forRegion(List.of(biome), 10, Double.POSITIVE_INFINITY);
        double initialDeficit = quota.deficit(biome);

        quota.increment(biome);
        assertEquals(initialDeficit - 1, quota.deficit(biome), 1e-9);

        quota.decrement(biome);
        assertEquals(initialDeficit, quota.deficit(biome), 1e-9);
    }

    @Test
    void deficitIsNegativeInfinityOnceTheSoftCapIsReached() {
        BiomeId biome = id("biome");
        // one biome -> totalWeight = 1.0 (uniform); cap = ceil(PATCH_COUNT_SLACK(4.0) * regionAreaBlocks2 / totalWeight)
        // = ceil(4.0 * 0.2 / 1.0) = ceil(0.8) = 1.
        FaceFairnessQuota quota = FaceFairnessQuota.forRegion(List.of(biome), 10, 0.2);

        quota.increment(biome);

        assertEquals(Double.NEGATIVE_INFINITY, quota.deficit(biome), "at the cap, deficit must sort dead last among candidates - never override legality, only reorder ties");
    }

    @Test
    void anInfiniteRegionAreaNeverCaps() {
        BiomeId biome = id("biome");
        FaceFairnessQuota quota = FaceFairnessQuota.forRegion(List.of(biome), 10, Double.POSITIVE_INFINITY);

        for (int i = 0; i < 1000; i++) {
            quota.increment(biome);
        }

        assertNotEquals(Double.NEGATIVE_INFINITY, quota.deficit(biome), "the simpler no-cap overload (POSITIVE_INFINITY) must never soft-cap a biome");
    }
}
