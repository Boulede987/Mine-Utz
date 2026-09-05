package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirrors the old {@code SeedRegionCacheTest}'s own coverage where a like-for-like
 * property still exists, adapted for the grid-free, organic-cluster cache: there is no
 * more "region"/"window" concept anywhere in this class's own public API (just {@link
 * SeedMeshCache#biomeAt}) — see that class's own doc for why.
 */
class SeedMeshCacheTest {

    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(4, 64);

    // Same 4-clique reasoning as the old SeedRegionCacheTest's own smallGraph() doc - a bare
    // 3-node path can never fill a hex face's own 6-neighbor ring once self-adjacency is banned.
    private static BiomeGraph smallGraph() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200), node("badlands", 150, 250)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10),
                AdjacencyRule.allow(id("desert"), id("plains"), 10),
                AdjacencyRule.allow(id("desert"), id("badlands"), 10),
                AdjacencyRule.allow(id("savanna"), id("badlands"), 10),
                AdjacencyRule.allow(id("plains"), id("badlands"), 10)
            )
        );
    }

    @Test
    void biomeAtIsConsistentAcrossRepeatedCalls() {
        SeedMeshCache cache = new SeedMeshCache(smallGraph(), SMALL_GRID, 1L);
        MacroCellPos cell = new MacroCellPos(0, 0);
        BiomeId first = cache.biomeAt(cell);
        BiomeId second = cache.biomeAt(cell);
        assertEquals(first, second);
    }

    @Test
    void sameWorldSeedProducesTheSameResultAcrossSeparateCaches() {
        MacroCellPos cell = new MacroCellPos(500, -300);
        BiomeId first = new SeedMeshCache(smallGraph(), SMALL_GRID, 5L).biomeAt(cell);
        BiomeId second = new SeedMeshCache(smallGraph(), SMALL_GRID, 5L).biomeAt(cell);
        assertEquals(first, second);
    }

    /**
     * The direct end-to-end regression test for the whole redesign: a real neighborhood of
     * cells, queried one at a time through the public cache API (exactly how real terrain
     * generation calls it), must never contain an illegal Moore-adjacent biome pair -
     * regardless of the fact that different cells in this neighborhood may have triggered
     * entirely independent cluster solves under the hood.
     */
    @Test
    void producesZeroIllegalAdjacenciesAcrossARealNeighborhood() {
        BiomeGraph graph = smallGraph();
        SeedMeshCache cache = new SeedMeshCache(graph, SMALL_GRID, 6L);

        List<MacroCellPos> cells = new ArrayList<>();
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                cells.add(new MacroCellPos(x, z));
            }
        }

        int pairs = 0;
        int illegal = 0;
        for (MacroCellPos cell : cells) {
            BiomeId here = cache.biomeAt(cell);
            for (MacroCellPos neighbor : cell.mooreNeighbors()) {
                BiomeId neighborBiome = cache.biomeAt(neighbor);
                pairs++;
                if (!here.equals(neighborBiome) && !graph.isAllowed(here, neighborBiome)) {
                    illegal++;
                }
            }
        }
        assertTrue(pairs > 0, "no adjacent pairs were even sampled - test setup itself is broken");
        assertEquals(0, illegal, "found an illegal Moore-adjacent biome pair (" + illegal + "/" + pairs + ")");
    }

    /** Two cells far enough apart that their own cluster solves can never overlap must still each resolve to a real, valid biome independently. */
    @Test
    void distantCellsResolveIndependently() {
        SeedMeshCache cache = new SeedMeshCache(smallGraph(), SMALL_GRID, 1L);
        BiomeId first = cache.biomeAt(new MacroCellPos(0, 0));
        BiomeId second = cache.biomeAt(new MacroCellPos(50_000, 50_000));
        assertTrue(first != null && second != null);
    }

    /**
     * The direct end-to-end test of the tentative/permanent redesign's own core promise
     * (see this whole redesign's own plan doc): a real query through the public {@code
     * biomeAt} API — Minecraft's own entry point — must promote its covering face to
     * PERMANENT, since that answer is about to be used to place real terrain.
     */
    @Test
    void queryingACellPromotesItsCoveringFaceToPermanent() {
        DecidedFaceStore faceStore = new DecidedFaceStore();
        DecidedVertexStore vertexStore = new DecidedVertexStore();
        SeedMeshCache cache = new SeedMeshCache(smallGraph(), SMALL_GRID, 1L, faceStore, vertexStore);

        cache.biomeAt(new MacroCellPos(0, 0));

        assertTrue(faceStore.permanentCount() > 0, "expected the actual queried cell's own covering face to be promoted to permanent");
    }

    /**
     * The other half of the same promise: a single cell query must NOT promote the whole
     * cluster it happened to trigger — only the one face actually covering that cell (plus
     * whichever of its own vertex triples are now fully settled). Everything else the
     * cluster solve touched as context should remain tentative, freely revisable later.
     */
    @Test
    void queryingOneCellLeavesMostOfItsOwnClusterMerelyTentative() {
        DecidedFaceStore faceStore = new DecidedFaceStore();
        DecidedVertexStore vertexStore = new DecidedVertexStore();
        SeedMeshCache cache = new SeedMeshCache(smallGraph(), SMALL_GRID, 1L, faceStore, vertexStore);

        cache.biomeAt(new MacroCellPos(0, 0));

        assertTrue(faceStore.tentativeCount() > faceStore.permanentCount(),
            "expected most of a fresh cluster solve to remain tentative after only one cell was actually queried - "
                + "tentative=" + faceStore.tentativeCount() + ", permanent=" + faceStore.permanentCount());
    }
}
