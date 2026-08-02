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

    private static BiomeNode node(BiomeId id, boolean universalFiller) {
        return new BiomeNode(
            id,
            new BiomeSizeRange(700, 1000),
            new BiomeShapeHints(0.5, 0.5, 0.5),
            new BiomeFeatureToggles(true, true),
            universalFiller,
            GrowthCohesionProfile.UNRESTRICTED
        );
    }

    @Test
    void validGraphWithFillerAllowedEverywhereBuildsFine() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT, false), node(SAVANNA, false), node(OCEAN, false), node(PLAINS, true)),
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

    @Test
    void missingUniversalFillerFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeGraph(
            List.of(node(DESERT, false), node(SAVANNA, false)),
            List.of(AdjacencyRule.allow(DESERT, SAVANNA, 10))
        ));
    }

    @Test
    void fillerMissingAllowRuleToSomeNodeFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeGraph(
            List.of(node(DESERT, false), node(SAVANNA, false), node(PLAINS, true)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, DESERT, 10)
                // PLAINS/SAVANNA rule missing -> filler not allowed everywhere
            )
        ));
    }

    @Test
    void ruleReferencingUnknownNodeFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeGraph(
            List.of(node(PLAINS, true)),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10))
        ));
    }

    @Test
    void duplicateRuleForSamePairFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeGraph(
            List.of(node(DESERT, false), node(SAVANNA, false), node(PLAINS, true)),
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
            List.of(node(DESERT, false), node(OCEAN, false), node(PLAINS, true)),
            List.of(
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, OCEAN, 10)
            )
        );

        assertFalse(graph.isAllowed(DESERT, OCEAN), "no explicit rule must mean forbidden");
    }

    @Test
    void sameBiomeIsAlwaysAllowedNextToItself() {
        BiomeGraph graph = new BiomeGraph(List.of(node(PLAINS, true)), List.of());

        assertTrue(graph.isAllowed(PLAINS, PLAINS));
    }

    @Test
    void selfAdjacencyRuleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AdjacencyRule.allow(DESERT, DESERT, 10));
    }

    @Test
    void blendWidthIsReadableFromRule() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT, false), node(PLAINS, true)),
            List.of(AdjacencyRule.allow(PLAINS, DESERT, 10))
        );

        assertEquals(10, graph.rule(DESERT, PLAINS).blendWidthBlocks());
        assertEquals(10, graph.rule(PLAINS, DESERT).blendWidthBlocks(), "rule lookup must be order-independent");
    }
}
