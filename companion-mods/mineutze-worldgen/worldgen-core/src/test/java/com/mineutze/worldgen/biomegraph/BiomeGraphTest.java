package com.mineutze.worldgen.biomegraph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeGraphTest {

    private static final String NAMESPACE = "mineutze_worldgen";
    private static final BiomeId DESERT = new BiomeId(NAMESPACE, "desert");
    private static final BiomeId SAVANNA = new BiomeId(NAMESPACE, "savanna");
    private static final BiomeId OCEAN = new BiomeId(NAMESPACE, "ocean");
    private static final BiomeId PLAINS = new BiomeId(NAMESPACE, "plains");
    private static final BiomeId FOREST = new BiomeId(NAMESPACE, "forest");

    private static BiomeNode node(BiomeId id) {
        return new BiomeNode(id, new BiomeSizeRange(700, 1000));
    }

    private static BiomeNode node(BiomeId id, int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeNode(id, new BiomeSizeRange(minWidthBlocks, maxWidthBlocks));
    }

    @Test
    void validGraphBuildsFine() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(OCEAN), node(PLAINS)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, OCEAN, 10)
            )
        );

        assertTrue(graph.isAllowed(DESERT, SAVANNA));
        assertTrue(graph.isAllowed(SAVANNA, DESERT), "adjacency must be symmetric");
    }

    /**
     * No node required universally legal anymore (see BiomeGraph's doc) - every
     * biome, even a filler-like one, restrictable like any other. OCEAN here has
     * only ONE rule (to DESERT, not SAVANNA) - far from universal - graph still
     * builds fine. Reachability/connectivity not checked at bare construction -
     * only {@code forProduction}'s opt-in min-legal-neighbors check validates a
     * real production graph.
     */
    @Test
    void graphWithNoNodeAllowedEverywhereStillBuildsFine() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(OCEAN)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10), AdjacencyRule.allow(DESERT, OCEAN, 10))
        );

        assertTrue(graph.isAllowed(DESERT, OCEAN));
        assertFalse(graph.isAllowed(SAVANNA, OCEAN));
    }

    /** A fully isolated node (zero rules to anything) no longer fails at bare construction time - only {@code forProduction}'s {@code requireMinLegalNeighbors} would catch this now, and only for callers that opt into it. */
    @Test
    void isolatedNodeStillBuildsAtTheBareConstructorLevel() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(OCEAN)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        );

        assertFalse(graph.isAllowed(OCEAN, DESERT));
        assertFalse(graph.isAllowed(OCEAN, SAVANNA));
    }

    @Test
    void ruleReferencingUnknownNodeFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeGraph(
            List.of(node(PLAINS)),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10))
        ));
    }

    @Test
    void duplicateRuleForSamePairFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(PLAINS)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(SAVANNA, DESERT, 10),
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10)
            )
        ));
    }

    @Test
    void noRuleMeansForbiddenByDefault() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT), node(OCEAN), node(PLAINS)),
            List.of(
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, OCEAN, 10)
            )
        );

        assertFalse(graph.isAllowed(DESERT, OCEAN), "no explicit rule must mean forbidden");
    }

    @Test
    void sameBiomeIsAllowedNextToItselfByDefault() {
        // 2026-09-14: default selfAdjacencyLimit changed from 0 to uncapped (see
        // BiomeNode's own doc) - a biome is allowed next to itself unless it explicitly
        // opts OUT with limit=0. See sameBiomeCanOptOutOfSelfAdjacency for that case.
        BiomeGraph graph = new BiomeGraph(List.of(node(PLAINS)), List.of());

        assertTrue(graph.isAllowed(PLAINS, PLAINS));
    }

    @Test
    void sameBiomeCanOptOutOfSelfAdjacency() {
        BiomeGraph graph = new BiomeGraph(List.of(new BiomeNode(PLAINS, new BiomeSizeRange(300, 600), 0)), List.of());

        assertFalse(graph.isAllowed(PLAINS, PLAINS));
    }

    @Test
    void selfAdjacencyRuleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AdjacencyRule.allow(DESERT, DESERT, 10));
    }

    @Test
    void blendWidthIsReadableFromRule() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT), node(PLAINS)),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10))
        );

        assertEquals(10, graph.rule(DESERT, PLAINS).blendWidthBlocks());
        assertEquals(10, graph.rule(PLAINS, DESERT).blendWidthBlocks(), "rule lookup must be order-independent");
    }
}
