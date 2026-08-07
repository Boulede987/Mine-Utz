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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CspIdentityAssignmentTest {

    private static final String NAMESPACE = "test";
    private static final MacroCellPos ARBITRARY_POSITION = new MacroCellPos(0, 0);

    @Test
    void solvesATrivialTwoFreeNodeGraph() {
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeGraph graph = graphOf(List.of(node(biomeA), node(biomeB)), List.of(AdjacencyRule.allow(biomeA, biomeB, 10)));

        SeedGraphNode node0 = SeedGraphNode.free(0, ARBITRARY_POSITION, Set.of(biomeA, biomeB));
        SeedGraphNode node1 = SeedGraphNode.free(1, ARBITRARY_POSITION, Set.of(biomeA, biomeB));
        SeedGraph seedGraph = new SeedGraph(List.of(node0, node1), Set.of(NodeEdge.of(0, 1)), false);

        Optional<Map<Integer, BiomeId>> result = CspIdentityAssignment.solve(seedGraph, graph);

        assertTrue(result.isPresent());
        assertTrue(graph.isAllowed(result.get().get(0), result.get().get(1)));
    }

    @Test
    void narrowsAFreeNodeThroughAc3PropagationFromAFixedNeighbor() {
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeId biomeC = id("c");
        BiomeGraph graph = graphOf(
            List.of(node(biomeA), node(biomeB), node(biomeC)),
            List.of(AdjacencyRule.allow(biomeA, biomeB, 10), AdjacencyRule.allow(biomeB, biomeC, 10))
        );

        // Domain is deliberately NOT pre-narrowed (unlike SeedGraphBuilder's
        // output) to exercise CspIdentityAssignment's own AC-3 pass.
        SeedGraphNode fixedNode = SeedGraphNode.fixed(0, ARBITRARY_POSITION, biomeA);
        SeedGraphNode freeNode = SeedGraphNode.free(1, ARBITRARY_POSITION, Set.of(biomeA, biomeB, biomeC));
        SeedGraph seedGraph = new SeedGraph(List.of(fixedNode, freeNode), Set.of(NodeEdge.of(0, 1)), false);

        Optional<Map<Integer, BiomeId>> result = CspIdentityAssignment.solve(seedGraph, graph);

        assertTrue(result.isPresent());
        assertEquals(biomeA, result.get().get(0));
        assertTrue(List.of(biomeA, biomeB).contains(result.get().get(1)), "c is never directly allowed next to a, so AC-3 must have pruned it");
    }

    @Test
    void returnsEmptyWhenTheGraphHasNoLegalColoring() {
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeId biomeC = id("c");
        BiomeId biomeD = id("d");
        // A-B and B-D bridge D and A into the graph so BiomeGraph's own eager
        // validation accepts this config, without making A-C or B-C legal -
        // the actual triangle under test stays genuinely uncolorable.
        BiomeGraph graph = graphOf(
            List.of(node(biomeA), node(biomeB), node(biomeC), node(biomeD)),
            List.of(
                AdjacencyRule.allow(biomeA, biomeB, 10),
                AdjacencyRule.allow(biomeB, biomeD, 10),
                AdjacencyRule.allow(biomeD, biomeC, 10)
            )
        );

        SeedGraphNode node0 = SeedGraphNode.free(0, ARBITRARY_POSITION, Set.of(biomeA));
        SeedGraphNode node1 = SeedGraphNode.free(1, ARBITRARY_POSITION, Set.of(biomeB));
        SeedGraphNode node2 = SeedGraphNode.free(2, ARBITRARY_POSITION, Set.of(biomeC));
        SeedGraph seedGraph = new SeedGraph(
            List.of(node0, node1, node2),
            Set.of(NodeEdge.of(0, 1), NodeEdge.of(1, 2), NodeEdge.of(0, 2)),
            false
        );

        Optional<Map<Integer, BiomeId>> result = CspIdentityAssignment.solve(seedGraph, graph);

        assertTrue(result.isEmpty(), "node1=b and node2=c are directly adjacent with no rule between them - no coloring can exist");
    }

    @Test
    void backtracksPastAFirstCandidateThatDeadEndsTwoNodesAway() {
        BiomeId biomeA = id("a");
        BiomeId biomeB = id("b");
        BiomeId biomeC = id("c");
        BiomeId biomeD = id("d");
        // Chain A-B-C-D: node0's first sorted candidate (a) leaves node1
        // with no legal value at all (neither c nor d is allowed next to a),
        // forcing a real backtrack to node0's second candidate (b).
        BiomeGraph graph = graphOf(
            List.of(node(biomeA), node(biomeB), node(biomeC), node(biomeD)),
            List.of(
                AdjacencyRule.allow(biomeA, biomeB, 10),
                AdjacencyRule.allow(biomeB, biomeC, 10),
                AdjacencyRule.allow(biomeC, biomeD, 10)
            )
        );

        SeedGraphNode node0 = SeedGraphNode.free(0, ARBITRARY_POSITION, Set.of(biomeA, biomeB));
        SeedGraphNode node1 = SeedGraphNode.free(1, ARBITRARY_POSITION, Set.of(biomeC, biomeD));
        SeedGraph seedGraph = new SeedGraph(List.of(node0, node1), Set.of(NodeEdge.of(0, 1)), false);

        Optional<Map<Integer, BiomeId>> result = CspIdentityAssignment.solve(seedGraph, graph);

        assertTrue(result.isPresent());
        assertEquals(Map.of(0, biomeB, 1, biomeC), result.get());
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
