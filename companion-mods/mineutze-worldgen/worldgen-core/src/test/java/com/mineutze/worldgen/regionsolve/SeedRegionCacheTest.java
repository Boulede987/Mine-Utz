package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedRegionCacheTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId PLAINS = id("plains");

    private static BiomeGraph smallGraph() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300, false), node("savanna", 200, 300, false), node("plains", 100, 200, true)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10)
            )
        );
    }

    private static SeedRegionCache newCache(long worldSeed) {
        return new SeedRegionCache(smallGraph(), MacroGridConfig.DEFAULT, worldSeed, 300, 20);
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
        SeedRegionCache cache = new SeedRegionCache(graph, MacroGridConfig.DEFAULT, 42L, 300, 20);
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
        BiomeId viaResultFor = cache.resultFor(MacroGridConfig.DEFAULT.regionOf(cell)).assignment().get(cell);

        assertEquals(viaResultFor, viaBiomeAt);
        assertNotNull(viaBiomeAt);
    }

    @Test
    void onRegionSolvedFiresExactlyOncePerRegionNeverOnACacheHit() {
        List<RegionPos> solvedRegions = new ArrayList<>();
        SeedRegionCache cache = new SeedRegionCache(
            smallGraph(), MacroGridConfig.DEFAULT, 9L, 300, 20,
            (region, result) -> solvedRegions.add(region)
        );
        RegionPos region = new RegionPos(1, 1);

        cache.resultFor(region);
        cache.resultFor(region);
        cache.biomeAt(new MacroCellPos(1 * MacroGridConfig.DEFAULT.regionSizeCellsPerSide(), 1 * MacroGridConfig.DEFAULT.regionSizeCellsPerSide()));

        assertEquals(List.of(region), solvedRegions, "callback must fire exactly once, only for the actual solve");
    }
}
