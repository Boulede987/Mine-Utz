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
        return new BiomeNode(
            id,
            new BiomeSizeRange(700, 1000),
            new BiomeShapeHints(0.5, 0.5, 0.5),
            new BiomeFeatureToggles(true, true),
            GrowthCohesionProfile.UNRESTRICTED
        );
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
     * No node is required to be universally legal anymore (see BiomeGraph's own
     * doc) - every biome, including whichever one plays a filler-like role, can be
     * restricted like any other. OCEAN here has only ONE rule (to DESERT, not
     * SAVANNA) - far from universally legal - and the graph still builds fine,
     * since that single rule keeps it within GapFiller's bridging reach of every
     * other node (see {@code isolatedNodeWithNoBridgeFailsFast} for the case
     * where a node is restricted too far to bridge at all).
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

    /**
     * A node with zero rules to anything can never legally touch another biome no
     * matter how GapFiller's bounded neighbor-reassignment tries - if it and
     * another biome ever end up spatially adjacent during world gen, that's an
     * unrecoverable dead end. Catching that at graph-construction time, not a
     * runtime crash mid-world-gen.
     */
    @Test
    void isolatedNodeWithNoBridgeFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(OCEAN)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        ));
    }

    /**
     * The real-world case this check exists for: not a fully isolated node (that's
     * the case above), but two biomes that ARE connected through the rest of the
     * graph - just too far apart for GapFiller's bounded neighbor-reassignment to
     * ever actually bridge at a real point of contact (found via badlands/forest
     * needing exactly the maximum supported 3-hop path badlands-desert-plains-
     * forest; this chain pushes one hop past that). DESERT-SAVANNA-PLAINS-OCEAN-
     * FOREST is a straight line, so DESERT to FOREST is 4 hops - one more than
     * {@code MAX_BRIDGE_HOPS} - even though every adjacent pair in the chain is
     * legal and the graph as a whole is fully connected.
     */
    @Test
    void pairBeyondBridgeHopsFailsFastEvenWhenGraphIsFullyConnected() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(PLAINS), node(OCEAN), node(FOREST)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(SAVANNA, PLAINS, 10),
                AdjacencyRule.allow(PLAINS, OCEAN, 10),
                AdjacencyRule.allow(OCEAN, FOREST, 10)
            )
        ));
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
    void sameBiomeIsAlwaysAllowedNextToItself() {
        BiomeGraph graph = new BiomeGraph(List.of(node(PLAINS)), List.of());

        assertTrue(graph.isAllowed(PLAINS, PLAINS));
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
