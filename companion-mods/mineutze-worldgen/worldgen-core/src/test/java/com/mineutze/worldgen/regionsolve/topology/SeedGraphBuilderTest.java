package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeFeatureToggles;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedGraphBuilderTest {

    private static final String NAMESPACE = "test";

    // One interior seed per region: spacing wider than the region itself
    // means SeedPlacer's grid loop only ever runs once.
    private static final MacroGridConfig ONE_SEED_PER_REGION = new MacroGridConfig(64, 4);
    private static final int WIDE_SEED_SPACING_BLOCKS = 1000;

    @Test
    void fixedBoundaryCellsBecomeFixedNodesWithSingleValueDomain() {
        BiomeId plains = id("plains");
        BiomeGraph graph = graphOf(List.of(node(plains)), List.of());
        RegionSolveInput input = inputWith(graph, Map.of(new MacroCellPos(-1, 0), plains));

        SeedGraph seedGraph = SeedGraphBuilder.build(input, WIDE_SEED_SPACING_BLOCKS);

        SeedGraphNode fixedNode = seedGraph.nodes().stream().filter(SeedGraphNode::fixed).findFirst().orElseThrow();
        assertEquals(Set.of(plains), fixedNode.domain());
        assertEquals(plains, fixedNode.fixedBiome());
    }

    @Test
    void freeNodeDomainStaysFullWithNoFixedNeighbor() {
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeId biomeC = id("c");
        BiomeGraph graph = graphOf(
            List.of(node(biomeA), node(biomeB), node(biomeC)),
            List.of(AdjacencyRule.allow(biomeA, biomeB, 10), AdjacencyRule.allow(biomeB, biomeC, 10))
        );
        RegionSolveInput input = inputWith(graph, Map.of());

        SeedGraph seedGraph = SeedGraphBuilder.build(input, WIDE_SEED_SPACING_BLOCKS);

        assertEquals(1, seedGraph.nodes().size());
        SeedGraphNode onlyNode = seedGraph.node(0);
        assertFalse(onlyNode.fixed());
        assertEquals(Set.of(biomeA, biomeB, biomeC), onlyNode.domain());
        assertFalse(seedGraph.reseedRecommended());
    }

    @Test
    void freeNodeDomainNarrowsToIdentitiesAllowedNextToItsFixedNeighbor() {
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeId biomeC = id("c");
        BiomeGraph graph = graphOf(
            List.of(node(biomeA), node(biomeB), node(biomeC)),
            List.of(AdjacencyRule.allow(biomeA, biomeB, 10), AdjacencyRule.allow(biomeB, biomeC, 10))
        );
        RegionSolveInput input = inputWith(graph, Map.of(new MacroCellPos(-1, 0), biomeA));

        SeedGraph seedGraph = SeedGraphBuilder.build(input, WIDE_SEED_SPACING_BLOCKS);

        SeedGraphNode freeNode = seedGraph.nodes().stream().filter(candidate -> !candidate.fixed()).findFirst().orElseThrow();
        // Only 2 total points (1 fixed + 1 free) -> always exactly one edge between them.
        assertEquals(Set.of(biomeA, biomeB), freeNode.domain(), "c is never directly allowed next to a, so it must be excluded");
    }

    @Test
    void reseedRecommendedWhenAFreeNodeEndsUpWithNoLegalIdentity() {
        BiomeId biomeX = id("x");
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeId biomeY = id("y");
        // Chain X-A-B-Y, exactly at the 3-hop bridge limit, so BiomeGraph's
        // own eager validation still accepts this graph - but a free node
        // wedged directly between fixed X and fixed Y has nothing in common
        // between "allowed next to X" ({x, a}) and "allowed next to y"
        // ({y, b}).
        BiomeGraph graph = graphOf(
            List.of(node(biomeX), node(biomeA), node(biomeB), node(biomeY)),
            List.of(
                AdjacencyRule.allow(biomeX, biomeA, 10),
                AdjacencyRule.allow(biomeA, biomeB, 10),
                AdjacencyRule.allow(biomeB, biomeY, 10)
            )
        );
        RegionSolveInput input = inputWith(graph, Map.of(
            new MacroCellPos(-1, 0), biomeX,
            new MacroCellPos(4, 0), biomeY
        ));

        SeedGraph seedGraph = SeedGraphBuilder.build(input, WIDE_SEED_SPACING_BLOCKS);

        // 3 total points (2 fixed + 1 free) are always fully connected, so
        // the free node necessarily touches both fixed neighbors.
        assertTrue(seedGraph.reseedRecommended());
    }

    private static RegionSolveInput inputWith(BiomeGraph graph, Map<MacroCellPos, BiomeId> fixedBoundaryCells) {
        return new RegionSolveInput(graph, ONE_SEED_PER_REGION, new RegionPos(0, 0), fixedBoundaryCells, 42L);
    }

    private static BiomeGraph graphOf(List<BiomeNode> nodes, List<AdjacencyRule> rules) {
        return new BiomeGraph(nodes, rules);
    }

    private static BiomeId id(String path) {
        return new BiomeId(NAMESPACE, path);
    }

    private static BiomeNode node(BiomeId id) {
        return new BiomeNode(
            id,
            new BiomeSizeRange(100, 200),
            new BiomeShapeHints(0.5, 0.5, 0.5),
            new BiomeFeatureToggles(true, true),
            GrowthCohesionProfile.UNRESTRICTED
        );
    }
}
