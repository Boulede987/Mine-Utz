package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedRegionCacheTest {

    // Not MacroGridConfig.DEFAULT - every test in this file is about cache MECHANICS
    // (hit/miss, no-force-solve, stitching, callback-once-ness, concurrent-locking
    // correctness), none of which need production region scale to exercise correctly.
    // DEFAULT's real region size (measured: ~262k cells, tens of seconds per solve, ~23MB
    // retained per region) turned this file's runtime from seconds into 20+ minutes -
    // same macroCellSizeBlocks as DEFAULT so cell-level assertions still mean the same
    // thing, small enough regionSizeCellsPerSide to stay fast, still above
    // MacroGridConfig's own WARP_MIN_REGION_SIZE so real (non-degenerate) warp behavior
    // is still exercised, which onRegionSolvedFiresExactlyOncePerRegionNeverOnACacheHit
    // specifically relies on (it probes the region's true CENTER, not its nominal corner).
    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(64, 16);

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId PLAINS = id("plains");

    private static BiomeGraph smallGraph() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10)
            )
        );
    }

    private static SeedRegionCache newCache(long worldSeed) {
        return new SeedRegionCache(smallGraph(), SMALL_GRID, worldSeed);
    }

    @Test
    void resultForReturnsTheSameCachedResultOnRepeatedCalls() {
        SeedRegionCache cache = newCache(1L);
        RegionPos region = new RegionPos(2, 2);

        RegionSolveResult first = cache.resultFor(region);
        RegionSolveResult second = cache.resultFor(region);

        assertSame(first, second, "second call must hit the cache, not re-solve");
    }

    @Test
    void solvingOneRegionNeverForceSolvesItsNeighbors() {
        SeedRegionCache cache = newCache(1L);
        cache.resultFor(new RegionPos(5, 5));

        assertFalse(cache.isAlreadySolved(new RegionPos(4, 5)), "west neighbor must not have been solved as a side effect");
        assertFalse(cache.isAlreadySolved(new RegionPos(6, 5)), "east neighbor must not have been solved as a side effect");
        assertFalse(cache.isAlreadySolved(new RegionPos(5, 4)), "north neighbor must not have been solved as a side effect");
        assertFalse(cache.isAlreadySolved(new RegionPos(5, 6)), "south neighbor must not have been solved as a side effect");
    }

    @Test
    void adjacentRegionsSolvedThroughTheSameCacheStitchWithNoRuleViolation() {
        BiomeGraph graph = smallGraph();
        SeedRegionCache cache = new SeedRegionCache(graph, SMALL_GRID, 42L);
        RegionPos west = new RegionPos(0, 0);
        RegionPos east = new RegionPos(1, 0);

        // Solve west first, then east - east's fixed boundary should pick up west's east edge.
        Map<MacroCellPos, BiomeId> westAssignment = cache.resultFor(west).assignment();
        Map<MacroCellPos, BiomeId> eastAssignment = cache.resultFor(east).assignment();

        for (Map.Entry<MacroCellPos, BiomeId> entry : eastAssignment.entrySet()) {
            for (MacroCellPos neighbor : entry.getKey().orthogonalNeighbors()) {
                BiomeId neighborBiome = westAssignment.get(neighbor);
                if (neighborBiome != null) {
                    assertTrue(
                        graph.isAllowed(entry.getValue(), neighborBiome),
                        entry.getValue() + " at " + entry.getKey() + " (region east) not allowed next to " + neighborBiome + " at " + neighbor + " (region west)"
                    );
                }
            }
        }
    }

    @Test
    void biomeAtMatchesTheSolvedRegionsAssignment() {
        SeedRegionCache cache = newCache(7L);
        MacroCellPos cell = new MacroCellPos(3, 3);

        BiomeId viaBiomeAt = cache.biomeAt(cell);
        BiomeId viaResultFor = cache.resultFor(SMALL_GRID.regionOf(cell, 7L)).assignment().get(cell);

        assertEquals(viaResultFor, viaBiomeAt);
        assertNotNull(viaBiomeAt);
    }

    @Test
    void onRegionSolvedFiresExactlyOncePerRegionNeverOnACacheHit() {
        List<RegionPos> solvedRegions = new ArrayList<>();
        SeedRegionCache cache = new SeedRegionCache(
            smallGraph(), SMALL_GRID, 9L,
            (region, result, durationMillis) -> solvedRegions.add(region)
        );
        RegionPos region = new RegionPos(1, 1);

        // Region CENTER, not its nominal corner - the region grid's own warp
        // (MacroGridConfig's doc) can push a cell exactly at the nominal boundary into a
        // different region than the one this test means to probe.
        int regionSize = SMALL_GRID.regionSizeCellsPerSide();
        cache.resultFor(region);
        cache.resultFor(region);
        cache.biomeAt(new MacroCellPos(region.x() * regionSize + regionSize / 2, region.z() * regionSize + regionSize / 2));

        assertEquals(List.of(region), solvedRegions, "callback must fire exactly once, only for the actual solve");
    }

    /**
     * Regression test for a real crash: chunk generation runs on a worker pool, so two
     * adjacent regions could genuinely be first-solved on different threads at the same
     * time, each seeing the other as "not solved yet" and proceeding unconstrained by it -
     * produced a forest cell directly touching a desert one with no rule between them,
     * crashing world gen in BorderBlend. Fire many threads at a grid of adjacent regions
     * simultaneously (a CountDownLatch maximizes actual overlap) and check every solved
     * region's borders are still legal against every neighbor it touches.
     */
    @Test
    void concurrentlySolvingAdjacentRegionsNeverProducesAnIllegalBorder() throws Exception {
        BiomeGraph graph = smallGraph();
        SeedRegionCache cache = new SeedRegionCache(graph, SMALL_GRID, 123L);

        List<RegionPos> regions = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                regions.add(new RegionPos(x, z));
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (RegionPos region : regions) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return cache.resultFor(region);
            }));
        }
        startLatch.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        for (RegionPos region : regions) {
            Map<MacroCellPos, BiomeId> assignment = cache.resultFor(region).assignment();
            for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
                for (MacroCellPos neighbor : entry.getKey().orthogonalNeighbors()) {
                    BiomeId neighborBiome = cache.biomeAt(neighbor);
                    assertTrue(
                        graph.isAllowed(entry.getValue(), neighborBiome),
                        entry.getValue() + " at " + entry.getKey() + " not allowed next to " + neighborBiome + " at " + neighbor
                    );
                }
            }
        }
    }
}
