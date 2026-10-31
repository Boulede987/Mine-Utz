package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.GroupData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the editor's live validation says about the rules the world generator itself rejects. */
class GraphValidationTest {

    private static NodeData node(String path, int minWidthBlocks, int maxWidthBlocks, String requirement) {
        return new NodeData("test", path, minWidthBlocks, maxWidthBlocks, Integer.MAX_VALUE, requirement);
    }

    private static EdgeData edge(String first, String second) {
        return new EdgeData("test", first, "test", second, 10);
    }

    private static final List<EdgeData> BEACH_OCEAN_ONLY = List.of(edge("beach", "ocean"), edge("ocean", "plains"));

    /** Beach, ocean and plains; beach borders only the ocean. */
    private static BiomeGraphData catalog(int beachMin, int beachMax, String beachRequirement, List<GroupData> groups) {
        return new BiomeGraphData(
            List.of(node("beach", beachMin, beachMax, beachRequirement), node("ocean", 650, 1000, ""), node("plains", 300, 700, "")),
            BEACH_OCEAN_ONLY,
            groups
        );
    }

    private static boolean someErrorContains(BiomeGraphData data, String fragment) {
        return GraphValidation.validate(data).errors().stream().anyMatch(error -> error.contains(fragment));
    }

    @Test
    void aRequirementOnALegalNeighborRaisesNoRequirementError() {
        BiomeGraphData data = catalog(300, 700, "test:ocean >= 2", List.of());

        assertTrue(GraphValidation.validate(data).errors().stream().noneMatch(error -> error.contains("could never be met")), GraphValidation.validate(data).errors().toString());
    }

    @Test
    void aRequirementOnABiomeThatMayNeverBeAdjacentIsAnErrorNamingBoth() {
        BiomeGraphData data = catalog(300, 700, "test:plains >= 2", List.of());

        assertTrue(someErrorContains(data, "test:plains is not a legal neighbor of test:beach"), GraphValidation.validate(data).errors().toString());
    }

    @Test
    void aRequirementBiggerThanAnyClusterOfTheBiomeCouldShareIsAnError() {
        BiomeGraphData data = catalog(300, 700, "test:ocean >= 40", List.of());

        assertTrue(someErrorContains(data, "could never be met"), GraphValidation.validate(data).errors().toString());
    }

    @Test
    void aSizeRangeHoldingNoWholeCellIsAnErrorTheGeneratorWouldRejectAtWorldCreation() {
        BiomeGraphData data = catalog(320, 330, "", List.of());

        assertTrue(someErrorContains(data, "contains no whole number"), GraphValidation.validate(data).errors().toString());
    }

    @Test
    void aGroupThatCanBeMetThroughOneMemberIsAccepted() {
        BiomeGraphData data = catalog(300, 700, "wet >= 2", List.of(new GroupData("wet", List.of("test:ocean", "test:plains"))));

        assertTrue(GraphValidation.validate(data).constructs());
        assertTrue(GraphValidation.validate(data).errors().stream().noneMatch(error -> error.contains("could never be met")), GraphValidation.validate(data).errors().toString());
    }

    @Test
    void aRequirementNamingAGroupThatDoesNotExistBlocksSaving() {
        GraphValidation.ValidationResult result = GraphValidation.validate(catalog(300, 700, "wet >= 2", List.of()));

        assertFalse(result.constructs());
        assertTrue(result.constructionError().contains("wet"), result.constructionError());
    }

    @Test
    void aRequirementThatCannotBeReadBlocksSaving() {
        GraphValidation.ValidationResult result = GraphValidation.validate(catalog(300, 700, "test:ocean >=", List.of()));

        assertFalse(result.constructs());
        assertTrue(result.constructionError().contains("test:beach"), result.constructionError());
    }

    @Test
    void aGroupHoldingABiomeThatDoesNotExistBlocksSaving() {
        GraphValidation.ValidationResult result = GraphValidation.validate(catalog(300, 700, "", List.of(new GroupData("wet", List.of("test:zzz")))));

        assertFalse(result.constructs());
        assertTrue(result.constructionError().contains("test:zzz"), result.constructionError());
    }
}
