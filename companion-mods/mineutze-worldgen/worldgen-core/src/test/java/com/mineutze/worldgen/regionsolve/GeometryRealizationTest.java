package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.SeedGraph;
import com.mineutze.worldgen.regionsolve.topology.SeedGraphNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryRealizationTest {

    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(64, 8);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final long WORLD_SEED = 42L;

    @Test
    void aSingleFreePatchGrowsToFillTheWholeRegion() {
        BiomeId solo = TestBiomeNodes.id("solo");
        BiomeGraph graph = new BiomeGraph(List.of(TestBiomeNodes.node("solo", 100, 2000)), List.of());
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(), WORLD_SEED);

        SeedGraph seedGraph = seedGraphOf(SeedGraphNode.free(0, new MacroCellPos(3, 3), Set.of(solo)));
        Map<MacroCellPos, BiomeId> result = GeometryRealization.realize(seedGraph, Map.of(0, solo), input);

        assertEquals(regionCellCount(), result.size());
        assertTrue(result.values().stream().allMatch(solo::equals));
    }

    @Test
    void twoCompatiblePatchesGrowingSimultaneouslyNeverProduceAnIllegalAdjacency() {
        BiomeId biomeA = TestBiomeNodes.id("a");
        BiomeId biomeB = TestBiomeNodes.id("b");
        BiomeGraph graph = new BiomeGraph(
            List.of(TestBiomeNodes.node("a", 100, 300), TestBiomeNodes.node("b", 100, 300)),
            List.of(AdjacencyRule.allow(biomeA, biomeB, 10))
        );
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(), WORLD_SEED);

        SeedGraph seedGraph = seedGraphOf(
            SeedGraphNode.free(0, new MacroCellPos(1, 1), Set.of(biomeA)),
            SeedGraphNode.free(1, new MacroCellPos(6, 6), Set.of(biomeB))
        );
        Map<MacroCellPos, BiomeId> result = GeometryRealization.realize(seedGraph, Map.of(0, biomeA, 1, biomeB), input);

        assertEquals(regionCellCount(), result.size(), "GapFiller guarantees full coverage of whatever growth didn't claim");
        assertNoIllegalAdjacency(result, graph);
    }

    @Test
    void aSeedSpotBlockedByFixedBoundaryIsSkippedAndReclaimedByGapFiller() {
        BiomeId friend = TestBiomeNodes.id("friend");
        BiomeId bridge = TestBiomeNodes.id("bridge");
        BiomeId stranger = TestBiomeNodes.id("stranger");
        // friend-bridge-stranger bridges the pair within BiomeGraph's own hop
        // budget without ever making friend directly legal next to stranger.
        BiomeGraph graph = new BiomeGraph(
            List.of(TestBiomeNodes.node("friend", 100, 300), TestBiomeNodes.node("bridge", 100, 300), TestBiomeNodes.node("stranger", 100, 300)),
            List.of(AdjacencyRule.allow(friend, bridge, 10), AdjacencyRule.allow(bridge, stranger, 10))
        );
        MacroCellPos seedCell = new MacroCellPos(0, 0);
        MacroCellPos fixedNeighbor = new MacroCellPos(-1, 0);
        RegionSolveInput input = new RegionSolveInput(graph, SMALL_GRID, REGION, Map.of(fixedNeighbor, stranger), WORLD_SEED);

        SeedGraph seedGraph = seedGraphOf(SeedGraphNode.free(0, seedCell, Set.of(friend)));
        Map<MacroCellPos, BiomeId> result = GeometryRealization.realize(seedGraph, Map.of(0, friend), input);

        assertEquals(regionCellCount(), result.size());
        assertFalse(result.containsValue(friend), "friend's only seed spot was illegal against the fixed stranger neighbor, so it never got placed");
        assertNoIllegalAdjacency(result, graph);
    }

    private static void assertNoIllegalAdjacency(Map<MacroCellPos, BiomeId> result, BiomeGraph graph) {
        for (Map.Entry<MacroCellPos, BiomeId> entry : result.entrySet()) {
            for (MacroCellPos neighbor : entry.getKey().orthogonalNeighbors()) {
                BiomeId neighborBiome = result.get(neighbor);
                if (neighborBiome != null) {
                    assertTrue(graph.isAllowed(entry.getValue(), neighborBiome),
                        entry.getValue() + " at " + entry.getKey() + " illegally touches " + neighborBiome + " at " + neighbor);
                }
            }
        }
    }

    private static SeedGraph seedGraphOf(SeedGraphNode... nodes) {
        return new SeedGraph(List.of(nodes), Set.of(), false);
    }

    private static int regionCellCount() {
        return SMALL_GRID.regionSizeCellsPerSide() * SMALL_GRID.regionSizeCellsPerSide();
    }
}
