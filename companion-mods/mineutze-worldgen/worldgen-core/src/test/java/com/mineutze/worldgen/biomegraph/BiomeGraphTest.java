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
     * SAVANNA) - far from universally legal - and the graph still builds fine.
     * A node's overall reachability/connectivity is no longer checked at bare
     * construction time at all (see this class's own git history: the
     * BridgeHopReachability pre-check this comment used to describe was removed as
     * providing little real signal beyond what {@link
     * #requireEveryLocalNeighborhoodSolvable} already proves directly) - only
     * {@code forProduction}'s opt-in checks (min-legal-neighbors,
     * every-local-neighborhood-solvable) still validate a real production graph.
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

    /** A fully isolated node (zero rules to anything) no longer fails at bare construction time - only {@code forProduction}'s {@code requireMinLegalNeighbors} (or {@code requireEveryLocalNeighborhoodSolvable}) would catch this now, and only for callers that opt into it. */
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
    void sameBiomeIsNeverAllowedNextToItself() {
        BiomeGraph graph = new BiomeGraph(List.of(node(PLAINS)), List.of());

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

    /**
     * DESERT-SAVANNA-PLAINS is a full triangle (every pair mutually legal, not just a
     * chain) and FOREST is a hub legal to all three but to nothing else. With only these
     * 4 nodes total and ringLength capped at 3, every realizable 3-ring is exactly one of
     * {DESERT,SAVANNA,PLAINS}, {DESERT,SAVANNA,FOREST}, {DESERT,PLAINS,FOREST}, or
     * {SAVANNA,PLAINS,FOREST} - and since self-adjacency is banned, a ring member can never
     * solve its OWN ring, so each of these 4 rings needs the one node NOT in it (the other
     * clique member, or FOREST) to be legal against all 3 members simultaneously. Because
     * DESERT/SAVANNA/PLAINS are pairwise legal AND each individually legal to FOREST, that
     * always holds - a genuinely realizable "solvable only via a node outside the ring"
     * case, not degenerate self-adjacency. (A 5th node like the old OCEAN would leave a
     * 4-ring with zero nodes free to solve it at all once self-adjacency can't fill in -
     * see this method's own doc on why maxNeighbors must stay below the total node count
     * for a hub fixture to mean anything.)
     */
    @Test
    void requireEveryLocalNeighborhoodSolvablePassesWhenARingHasAnOutsideHub() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(PLAINS), node(FOREST)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(SAVANNA, PLAINS, 10),
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(FOREST, DESERT, 10),
                AdjacencyRule.allow(FOREST, SAVANNA, 10),
                AdjacencyRule.allow(FOREST, PLAINS, 10)
            )
        );

        graph.requireEveryLocalNeighborhoodSolvable(3);
    }

    /**
     * A bare 4-cycle (no diagonal, no outside hub) - DESERT-SAVANNA-PLAINS-OCEAN-DESERT
     * IS a real, legal ring (every consecutive pair allowed), but no biome is legal
     * against all 4 ring members at once (DESERT isn't legal to PLAINS, its opposite
     * corner, and neither ring member nor anything else bridges that): this is the
     * exact real dead end {@code SeamGrowthRasterizer} would otherwise only discover
     * mid-generation.
     */
    @Test
    void requireEveryLocalNeighborhoodSolvableThrowsOnAGenuineDeadEnd() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(PLAINS), node(OCEAN)),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(SAVANNA, PLAINS, 10),
                AdjacencyRule.allow(PLAINS, OCEAN, 10),
                AdjacencyRule.allow(OCEAN, DESERT, 10)
            )
        );

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> graph.requireEveryLocalNeighborhoodSolvable(4));
        assertTrue(thrown.getMessage().contains("no biome is legal against every member"), "expected a diagnostic naming the unsolvable ring, got: " + thrown.getMessage());
    }

    /** No legal ring of the requested length exists at all (a bare star/hub has no cycle) - vacuously passes, since there's nothing realizable to check, not a false confirmation of anything. */
    @Test
    void requireEveryLocalNeighborhoodSolvablePassesVacuouslyWhenNoLegalRingExists() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT), node(SAVANNA), node(PLAINS), node(OCEAN)),
            List.of(
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, OCEAN, 10)
            )
        );

        graph.requireEveryLocalNeighborhoodSolvable(4);
    }
}
