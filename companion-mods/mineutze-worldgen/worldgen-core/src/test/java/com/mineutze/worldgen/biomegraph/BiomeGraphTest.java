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
     * builds fine. Reachability/connectivity no longer checked at bare
     * construction (git history: BridgeHopReachability pre-check removed, little
     * signal beyond what {@link #requireEveryLocalNeighborhoodSolvable} already
     * proves) - only {@code forProduction}'s opt-in checks (min-legal-neighbors,
     * every-local-neighborhood-solvable) validate a real production graph.
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

    /**
     * DESERT-SAVANNA-PLAINS = full triangle (every pair mutually legal, not just chain),
     * FOREST = hub legal to all three, nothing else. 4 nodes total, ringLength capped at
     * 3: every realizable 3-ring is exactly one of {DESERT,SAVANNA,PLAINS},
     * {DESERT,SAVANNA,FOREST}, {DESERT,PLAINS,FOREST}, {SAVANNA,PLAINS,FOREST} - since
     * self-adjacency banned, ring member can never solve its own ring, so each needs the
     * one node NOT in it (other clique member, or FOREST) legal against all 3
     * simultaneously. DESERT/SAVANNA/PLAINS pairwise legal AND each legal to FOREST, so
     * always holds - real "solvable only via node outside ring" case, not degenerate
     * self-adjacency. (5th node like old OCEAN would leave a 4-ring with zero free nodes
     * once self-adjacency can't fill in - see this method's doc on why maxNeighbors must
     * stay below total node count for a hub fixture to mean anything.)
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
     * Bare 4-cycle (no diagonal, no outside hub) - DESERT-SAVANNA-PLAINS-OCEAN-DESERT
     * IS a real legal ring (every consecutive pair allowed), but no biome legal against
     * all 4 members at once (DESERT not legal to PLAINS, its opposite corner, nothing
     * bridges that): exact real dead end {@code SeamGrowthRasterizer} would otherwise
     * only discover mid-generation.
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

    /**
     * DESERT and OCEAN share one size range (700-1000), PLAINS a different one
     * (300-600). DESERT's only legal neighbor is OCEAN, same tier - real structural
     * gap: DESERT can never border a differently-sized biome, so its size correction
     * has strictly less to work with than a biome with a real cross-tier option (see
     * {@code BiomeGraph.requireEveryBiomeHasACrossTierNeighbor}'s doc for the measured
     * consequence). OCEAN and PLAINS each have a real cross-tier neighbor, pass.
     */
    @Test
    void requireEveryBiomeHasACrossTierNeighborThrowsWhenANodeIsStuckInItsOwnTier() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT, 700, 1000), node(OCEAN, 700, 1000), node(PLAINS, 300, 600)),
            List.of(
                AdjacencyRule.allow(DESERT, OCEAN, 10),
                AdjacencyRule.allow(OCEAN, PLAINS, 10)
            )
        );

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, graph::requireEveryBiomeHasACrossTierNeighbor);
        assertTrue(thrown.getMessage().contains(DESERT.toString()), "expected a diagnostic naming the stuck biome, got: " + thrown.getMessage());
    }

    /** Same shape as {@link #requireEveryBiomeHasACrossTierNeighborThrowsWhenANodeIsStuckInItsOwnTier}, but DESERT also gets a direct PLAINS rule - every node now has a real cross-tier neighbor, passes cleanly. */
    @Test
    void requireEveryBiomeHasACrossTierNeighborPassesWhenEveryNodeHasOne() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node(DESERT, 700, 1000), node(OCEAN, 700, 1000), node(PLAINS, 300, 600)),
            List.of(
                AdjacencyRule.allow(DESERT, OCEAN, 10),
                AdjacencyRule.allow(OCEAN, PLAINS, 10),
                AdjacencyRule.allow(DESERT, PLAINS, 10)
            )
        );

        graph.requireEveryBiomeHasACrossTierNeighbor();
    }
}
