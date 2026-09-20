package com.mineutze.worldgen.biomegraph;

import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.GroupData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeGraphJsonTest {

    private static BiomeGraphData sample() {
        return new BiomeGraphData(
            List.of(
                new NodeData("test", "beach", 300, 700, 3, "oceans >= 2 and (forests >= 1 or test:plains >= 1)"),
                new NodeData("test", "ocean", 650, 1000, 3, "")
            ),
            List.of(new EdgeData("test", "beach", "test", "ocean", 10)),
            List.of(new GroupData("oceans", List.of("test:ocean")), new GroupData("forests", List.of()))
        );
    }

    @Test
    void aGraphWithGroupsAndRequirementsSurvivesAJsonRoundTrip() {
        assertEquals(sample(), BiomeGraphJson.fromJson(BiomeGraphJson.toJson(sample())));
    }

    @Test
    void aFileWithoutGroupsOrRequirementsLoadsWithNone() {
        String json = "{\"nodes\":[{\"namespace\":\"test\",\"path\":\"a\",\"minWidthBlocks\":300,\"maxWidthBlocks\":700,\"selfAdjacencyLimit\":3}],\"edges\":[]}";

        BiomeGraphData data = BiomeGraphJson.fromJson(json);

        assertEquals("", data.nodes().get(0).requirement());
        assertTrue(data.groups().isEmpty());
    }

    @Test
    void twoArgumentConstructionMeansNoGroups() {
        assertTrue(new BiomeGraphData(List.of(), List.of()).groups().isEmpty());
    }

    /** The field was replaced; loading a requirement written in the old form would silently drop it, so it is refused. */
    @Test
    void anOldStyleRequiredNeighborEntryIsRefusedNotIgnored() {
        String json = "{\"nodes\":[{\"namespace\":\"test\",\"path\":\"a\",\"minWidthBlocks\":300,\"maxWidthBlocks\":700,\"selfAdjacencyLimit\":3,"
            + "\"requiredNeighbors\":[{\"namespace\":\"test\",\"path\":\"b\",\"minCount\":1}]}],\"edges\":[]}";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> BiomeGraphJson.fromJson(json));

        assertTrue(failure.getMessage().contains("requiredNeighbors") && failure.getMessage().contains("test:a"), failure.getMessage());
    }

    @Test
    void anEmptyOldStyleListIsHarmless() {
        String json = "{\"nodes\":[{\"namespace\":\"test\",\"path\":\"a\",\"minWidthBlocks\":300,\"maxWidthBlocks\":700,\"selfAdjacencyLimit\":3,\"requiredNeighbors\":[]}],\"edges\":[]}";

        assertEquals("", BiomeGraphJson.fromJson(json).nodes().get(0).requirement());
    }

    @Test
    void aGroupWithoutANameIsRefused() {
        String json = "{\"nodes\":[],\"edges\":[],\"groups\":[{\"members\":[\"test:a\"]}]}";

        assertThrows(IllegalArgumentException.class, () -> BiomeGraphJson.fromJson(json));
    }
}
