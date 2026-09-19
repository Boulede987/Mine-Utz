package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.RequiredNeighbor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the graph editor's pure edit functions only. The HTTP handlers write straight to the
 * production data file ({@link GraphEditorFiles#DATA_FILE}), so they are deliberately not
 * exercised from a unit test.
 */
class GraphEditorServerTest {

    private static final int SELF_ADJACENCY_LIMIT = 3;

    private static NodeData node(String path, int minWidthBlocks, int maxWidthBlocks, List<RequiredNeighbor> requiredNeighbors) {
        return new NodeData("test", path, minWidthBlocks, maxWidthBlocks, SELF_ADJACENCY_LIMIT, requiredNeighbors);
    }

    private static BiomeGraphData graph() {
        return new BiomeGraphData(
            List.of(
                node("target", 500, 800, List.of(new RequiredNeighbor("test", "other", 1))),
                node("other", 300, 700, List.of())
            ),
            List.of(new EdgeData("test", "target", "test", "other", 10))
        );
    }

    @Test
    void withSizeChangesOnlyTheTargetBiomesMinAndMax() {
        BiomeGraphData updated = GraphEditorServer.withSize(graph(), "test", "target", 650, 1200).orElseThrow();

        NodeData target = updated.nodes().get(0);
        assertEquals(650, target.minWidthBlocks());
        assertEquals(1200, target.maxWidthBlocks());
    }

    @Test
    void withSizeKeepsEveryOtherFieldOfTheTargetBiome() {
        BiomeGraphData updated = GraphEditorServer.withSize(graph(), "test", "target", 650, 1200).orElseThrow();

        NodeData target = updated.nodes().get(0);
        assertEquals(SELF_ADJACENCY_LIMIT, target.selfAdjacencyLimit());
        assertEquals(List.of(new RequiredNeighbor("test", "other", 1)), target.requiredNeighbors());
    }

    @Test
    void withSizeLeavesOtherBiomesAndEveryEdgeUntouched() {
        BiomeGraphData original = graph();

        BiomeGraphData updated = GraphEditorServer.withSize(original, "test", "target", 650, 1200).orElseThrow();

        assertEquals(original.nodes().get(1), updated.nodes().get(1));
        assertEquals(original.edges(), updated.edges());
        assertEquals(original.nodes().size(), updated.nodes().size());
    }

    @Test
    void withSizeReturnsEmptyForABiomeThatDoesNotExist() {
        Optional<BiomeGraphData> updated = GraphEditorServer.withSize(graph(), "test", "missing", 650, 1200);

        assertTrue(updated.isEmpty());
    }

    @Test
    void anInvertedSizeRangeIsRejectedByValidationSoItWouldNeverBeSaved() {
        assertTrue(GraphValidation.validate(graph()).constructs(), "control: the unedited test graph must be valid");
        BiomeGraphData inverted = GraphEditorServer.withSize(graph(), "test", "target", 900, 400).orElseThrow();

        assertFalse(GraphValidation.validate(inverted).constructs());
    }

    @Test
    void aNonPositiveMinimumIsRejectedByValidationSoItWouldNeverBeSaved() {
        BiomeGraphData nonPositive = GraphEditorServer.withSize(graph(), "test", "target", 0, 400).orElseThrow();

        assertFalse(GraphValidation.validate(nonPositive).constructs());
    }
}
