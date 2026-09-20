package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.RequiredNeighbor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the editor's live validation says about the rules the world generator itself rejects. */
class GraphValidationTest {

    private static NodeData node(String path, int minWidthBlocks, int maxWidthBlocks, List<RequiredNeighbor> requirements) {
        return new NodeData("test", path, minWidthBlocks, maxWidthBlocks, Integer.MAX_VALUE, requirements);
    }

    private static EdgeData edge(String first, String second) {
        return new EdgeData("test", first, "test", second, 10);
    }

    /** Beach, ocean and plains, all mutually legal except beach-plains. */
    private static BiomeGraphData catalog(int beachMin, int beachMax, List<RequiredNeighbor> beachRequirements, List<EdgeData> edges) {
        return new BiomeGraphData(
            List.of(node("beach", beachMin, beachMax, beachRequirements), node("ocean", 650, 1000, List.of()), node("plains", 300, 700, List.of())),
            edges
        );
    }

    private static final List<EdgeData> BEACH_OCEAN_ONLY = List.of(edge("beach", "ocean"), edge("ocean", "plains"));

    private static boolean someErrorContains(BiomeGraphData data, String fragment) {
        return GraphValidation.validate(data).errors().stream().anyMatch(error -> error.contains(fragment));
    }

    @Test
    void aRequirementOnALegalNeighborRaisesNoRequirementError() {
        BiomeGraphData data = catalog(300, 700, List.of(new RequiredNeighbor("test", "ocean", 2)), BEACH_OCEAN_ONLY);

        assertTrue(GraphValidation.validate(data).errors().stream().noneMatch(error -> error.contains("requires a border")), GraphValidation.validate(data).errors().toString());
    }

    @Test
    void aRequirementOnABiomeThatMayNeverBeAdjacentIsAnErrorNamingBoth() {
        BiomeGraphData data = catalog(300, 700, List.of(new RequiredNeighbor("test", "plains", 2)), BEACH_OCEAN_ONLY);

        assertTrue(someErrorContains(data, "test:beach requires a border with test:plains"), GraphValidation.validate(data).errors().toString());
    }

    @Test
    void aRequirementBiggerThanAnyClusterOfTheBiomeCouldShareIsAnError() {
        BiomeGraphData data = catalog(300, 700, List.of(new RequiredNeighbor("test", "ocean", 40)), BEACH_OCEAN_ONLY);

        assertTrue(someErrorContains(data, "could never be met"), GraphValidation.validate(data).errors().toString());
    }

    @Test
    void aSizeRangeHoldingNoWholeCellIsAnErrorTheGeneratorWouldRejectAtWorldCreation() {
        BiomeGraphData data = catalog(320, 330, List.of(), BEACH_OCEAN_ONLY);

        assertTrue(someErrorContains(data, "contains no whole number"), GraphValidation.validate(data).errors().toString());
    }
}
