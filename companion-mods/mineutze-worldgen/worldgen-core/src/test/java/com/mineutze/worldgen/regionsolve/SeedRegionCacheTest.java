package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic coverage restored now that {@link TriangleFieldGeometryRealization} is wired
 * in as the real algorithm behind {@link SeedRegionCache#solve} — the previous version
 * of this file was deleted mid-session when the old algorithm was removed and there was
 * nothing real left to exercise. Small grid (not {@link MacroGridConfig#DEFAULT}) so
 * these stay fast, same reasoning the deleted version documented.
 */
class SeedRegionCacheTest {

    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(4, 64);

    private static BiomeGraph smallGraph() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10)
            )
        );
    }

    @Test
    void resultForCachesAcrossCalls() {
        SeedRegionCache cache = new SeedRegionCache(smallGraph(), SMALL_GRID, 1L);
        RegionSolveResult first = cache.resultFor(new RegionPos(0, 0));
        RegionSolveResult second = cache.resultFor(new RegionPos(0, 0));
        assertSame(first, second);
    }

    @Test
    void neverForceSolvesANeighborRegion() {
        SeedRegionCache cache = new SeedRegionCache(smallGraph(), SMALL_GRID, 1L);
        cache.resultFor(new RegionPos(0, 0));
        assertFalse(cache.isAlreadySolved(new RegionPos(1, 0)), "solving one region must never force-solve an untouched neighbor");
    }

    @Test
    void onRegionSolvedFiresExactlyOncePerRegionNeverOnACacheHit() {
        AtomicInteger fireCount = new AtomicInteger();
        SeedRegionCache cache = new SeedRegionCache(smallGraph(), SMALL_GRID, 1L, (region, result, durationMillis) -> fireCount.incrementAndGet());
        cache.resultFor(new RegionPos(0, 0));
        cache.resultFor(new RegionPos(0, 0));
        cache.resultFor(new RegionPos(0, 0));
        assertEquals(1, fireCount.get());
    }

    @Test
    void everyCellInTheRegionIsAssigned() {
        SeedRegionCache cache = new SeedRegionCache(smallGraph(), SMALL_GRID, 1L);
        RegionSolveResult result = cache.resultFor(new RegionPos(0, 0));
        List<MacroCellPos> cells = SMALL_GRID.cellsInRegion(new RegionPos(0, 0), 1L);
        assertEquals(cells.size(), result.assignment().size());
    }

    @Test
    void sameWorldSeedProducesTheSameResultAcrossSeparateCaches() {
        RegionSolveResult first = new SeedRegionCache(smallGraph(), SMALL_GRID, 5L).resultFor(new RegionPos(2, -1));
        RegionSolveResult second = new SeedRegionCache(smallGraph(), SMALL_GRID, 5L).resultFor(new RegionPos(2, -1));
        assertEquals(first.assignment(), second.assignment());
    }

    @Test
    void differentRegionsProduceIndependentResults() {
        SeedRegionCache cache = new SeedRegionCache(smallGraph(), SMALL_GRID, 1L);
        RegionSolveResult regionA = cache.resultFor(new RegionPos(0, 0));
        RegionSolveResult regionB = cache.resultFor(new RegionPos(5, 5));
        assertTrue(cache.isAlreadySolved(new RegionPos(0, 0)));
        assertTrue(cache.isAlreadySolved(new RegionPos(5, 5)));
        assertFalse(regionA.assignment().keySet().stream().anyMatch(cell -> regionB.assignment().containsKey(cell)));
    }

    // Region side matches MacroGridConfig.DEFAULT's real 2048-block width (and therefore
    // its ~0.73 ratio to TriangleFieldGeometryRealization.TRIANGULATION_MARGIN_BLOCKS) -
    // SMALL_GRID's 256-block region is smaller than SEED_SPACING_BLOCKS (500) itself, so a
    // real face rarely has a centroid landing anywhere near it, which silently defeats the
    // cross-region fix without any bug being present (found by direct measurement, not
    // guessed). Coarser cells (64 vs 4) keep cellsInRegion small/fast despite the wider
    // block footprint.
    private static final MacroGridConfig CROSS_REGION_GRID = new MacroGridConfig(64, 32);

    /**
     * The key end-to-end regression test for cross-region boundary consistency: region
     * (1,0) is solved AFTER region (0,0), through the SAME cache, so it sees (0,0) as an
     * already-solved neighbor via {@link RegionBoundaryCollector} — its own free faces near
     * the shared border are legality-checked against whatever (0,0) already decided there,
     * the same guarantee {@link
     * com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignmentTest#everyAssignedFaceIsLegalAgainstItsAssignedAndFixedNeighbors}
     * already proves within one mesh, now applied across two independently-triangulated
     * regions.
     *
     * <p>Was rate-based ({@code < 0.05}) before {@link LegalityConstrainedRasterizer}
     * existed - now a hard zero, Moore (8-way) neighbors included, since that class
     * legality-constrains the FINAL rasterization against {@code fixedBoundaryCells} too
     * (already-committed cross-region context), not just the CSP's Delaunay-adjacent faces.
     */
    @Test
    void crossRegionProducesZeroIllegalAdjacencies() {
        BiomeGraph graph = smallGraph();
        SeedRegionCache cache = new SeedRegionCache(graph, CROSS_REGION_GRID, 6L);
        RegionSolveResult regionA = cache.resultFor(new RegionPos(0, 0));
        RegionSolveResult regionB = cache.resultFor(new RegionPos(1, 0));

        int pairs = 0;
        int illegal = 0;
        for (Map.Entry<MacroCellPos, BiomeId> entry : regionA.assignment().entrySet()) {
            MacroCellPos cell = entry.getKey();
            List<MacroCellPos> mooreNeighbors = new java.util.ArrayList<>(cell.orthogonalNeighbors());
            mooreNeighbors.addAll(cell.diagonalNeighbors());
            for (MacroCellPos neighbor : mooreNeighbors) {
                BiomeId neighborBiome = regionB.assignment().get(neighbor);
                if (neighborBiome != null) {
                    pairs++;
                    if (!graph.isAllowed(entry.getValue(), neighborBiome)) {
                        illegal++;
                    }
                }
            }
        }
        assertTrue(pairs > 0, "no cross-region border pairs were even sampled - test setup itself is broken");
        assertEquals(0, illegal, "found an illegal Moore-adjacent biome pair across the region (0,0)/(1,0) border (" + illegal + "/" + pairs + ")");
    }
}
