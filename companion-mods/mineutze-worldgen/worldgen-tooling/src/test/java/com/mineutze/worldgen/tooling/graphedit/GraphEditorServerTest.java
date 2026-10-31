package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.GroupData;
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

    private static NodeData node(String path, int minWidthBlocks, int maxWidthBlocks, String requirement) {
        return new NodeData("test", path, minWidthBlocks, maxWidthBlocks, SELF_ADJACENCY_LIMIT, requirement);
    }

    private static BiomeGraphData graph() {
        return new BiomeGraphData(
            List.of(
                node("target", 500, 800, "test:other >= 1"),
                node("other", 300, 700, "")
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
        assertEquals("test:other >= 1", target.requirement());
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

    /** Beach needs 1 edge with the group "wet" (ocean and plains). */
    private static BiomeGraphData threeBiomeGraph() {
        return new BiomeGraphData(
            List.of(node("beach", 100, 200, "wet >= 1"), node("ocean", 300, 700, ""), node("plains", 300, 700, "")),
            List.of(
                new EdgeData("test", "beach", "test", "ocean", 10),
                new EdgeData("test", "plains", "test", "beach", 10),
                new EdgeData("test", "ocean", "test", "plains", 10)
            ),
            List.of(new GroupData("wet", List.of("test:ocean", "test:plains")))
        );
    }

    @Test
    void withoutBiomeDropsTheBiomeAndKeepsTheOthers() {
        BiomeGraphData updated = GraphEditorServer.withoutBiome(threeBiomeGraph(), "test", "ocean").orElseThrow();

        assertEquals(List.of("beach", "plains"), updated.nodes().stream().map(NodeData::path).toList());
    }

    @Test
    void withoutBiomeDropsEveryAdjacencyTouchingItWhicheverSideItIsOn() {
        BiomeGraphData updated = GraphEditorServer.withoutBiome(threeBiomeGraph(), "test", "beach").orElseThrow();

        assertEquals(List.of(new EdgeData("test", "ocean", "test", "plains", 10)), updated.edges());
    }

    @Test
    void withoutBiomeTakesItOutOfEveryGroup() {
        BiomeGraphData updated = GraphEditorServer.withoutBiome(threeBiomeGraph(), "test", "ocean").orElseThrow();

        assertEquals(List.of(new GroupData("wet", List.of("test:plains"))), updated.groups());
    }

    @Test
    void withoutBiomeLeavesAValidGraphWhenOnlyAGroupHeldIt() {
        BiomeGraphData updated = GraphEditorServer.withoutBiome(threeBiomeGraph(), "test", "ocean").orElseThrow();

        assertTrue(GraphValidation.validate(updated).constructs());
    }

    @Test
    void withoutBiomeLeavesARequirementNamingItAsWrittenSoValidationRefusesTheRemoval() {
        BiomeGraphData namesOcean = GraphEditorServer.withRequirement(threeBiomeGraph(), "test", "beach", "test:ocean >= 1").orElseThrow();

        BiomeGraphData updated = GraphEditorServer.withoutBiome(namesOcean, "test", "ocean").orElseThrow();

        GraphValidation.ValidationResult result = GraphValidation.validate(updated);
        assertFalse(result.constructs());
        assertTrue(result.constructionError().contains("test:ocean"), result.constructionError());
    }

    @Test
    void withoutBiomeReturnsEmptyForABiomeThatDoesNotExist() {
        assertTrue(GraphEditorServer.withoutBiome(threeBiomeGraph(), "test", "missing").isEmpty());
    }

    @Test
    void withRequirementStoresTheCanonicalTextOfARequirementThatCanBeRead() {
        BiomeGraphData updated = GraphEditorServer.withRequirement(threeBiomeGraph(), "test", "beach", "wet>=2 and(test:ocean>=1 or test:plains>=1)").orElseThrow();

        assertEquals("wet >= 2 and (test:ocean >= 1 or test:plains >= 1)", updated.nodes().get(0).requirement());
    }

    @Test
    void withRequirementKeepsAnUnreadableTextAsWrittenSoValidationCanSayWhy() {
        BiomeGraphData updated = GraphEditorServer.withRequirement(threeBiomeGraph(), "test", "beach", "  wet >=  ").orElseThrow();

        assertEquals("wet >=", updated.nodes().get(0).requirement());
        assertFalse(GraphValidation.validate(updated).constructs());
    }

    @Test
    void aBlankRequirementClearsIt() {
        BiomeGraphData updated = GraphEditorServer.withRequirement(threeBiomeGraph(), "test", "beach", "   ").orElseThrow();

        assertEquals("", updated.nodes().get(0).requirement());
    }

    @Test
    void withRequirementChangesOnlyThatBiomeAndKeepsEdgesAndGroups() {
        BiomeGraphData original = threeBiomeGraph();

        BiomeGraphData updated = GraphEditorServer.withRequirement(original, "test", "ocean", "test:beach >= 1").orElseThrow();

        assertEquals(original.nodes().get(0), updated.nodes().get(0));
        assertEquals(original.edges(), updated.edges());
        assertEquals(original.groups(), updated.groups());
    }

    @Test
    void withRequirementReturnsEmptyForABiomeThatDoesNotExist() {
        assertTrue(GraphEditorServer.withRequirement(threeBiomeGraph(), "test", "missing", "wet >= 1").isEmpty());
    }

    @Test
    void withGroupAddsANewGroupAfterTheExistingOnes() {
        BiomeGraphData updated = GraphEditorServer.withGroup(threeBiomeGraph(), "coasts", List.of("test:beach"));

        assertEquals(List.of("wet", "coasts"), updated.groups().stream().map(GroupData::name).toList());
    }

    @Test
    void withGroupReplacesTheMembersOfAGroupOfTheSameNameInPlace() {
        BiomeGraphData updated = GraphEditorServer.withGroup(threeBiomeGraph(), "wet", List.of("test:ocean"));

        assertEquals(List.of(new GroupData("wet", List.of("test:ocean"))), updated.groups());
    }

    @Test
    void withoutGroupRemovesItAndReturnsEmptyForAnUnknownName() {
        assertTrue(GraphEditorServer.withoutGroup(threeBiomeGraph(), "wet").orElseThrow().groups().isEmpty());
        assertTrue(GraphEditorServer.withoutGroup(threeBiomeGraph(), "missing").isEmpty());
    }

    @Test
    void aGroupCannotBeRemovedWhileARequirementStillNamesIt() {
        BiomeGraphData updated = GraphEditorServer.withoutGroup(threeBiomeGraph(), "wet").orElseThrow();

        GraphValidation.ValidationResult result = GraphValidation.validate(updated);
        assertFalse(result.constructs());
        assertTrue(result.constructionError().contains("wet"), result.constructionError());
    }

    @Test
    void everyOtherEditKeepsTheGroups() {
        BiomeGraphData original = threeBiomeGraph();

        assertEquals(original.groups(), GraphEditorServer.withSize(original, "test", "beach", 300, 700).orElseThrow().groups());
    }
}
