package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjacencyReportTest {

    private static RawRuleTable tableWithLegalPairs(List<BiomeId> biomes, BiomeId[]... legalPairs) {
        List<NodeData> nodes = biomes.stream().map(biome -> new NodeData(biome.namespace(), biome.path(), 300, 700, Integer.MAX_VALUE, "")).toList();
        List<EdgeData> edges = java.util.Arrays.stream(legalPairs)
            .map(pair -> new EdgeData(pair[0].namespace(), pair[0].path(), pair[1].namespace(), pair[1].path(), 10))
            .toList();
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    /** A is legal with B and C, B is not legal with C: B and C are stuck, and B's only legal partner A touches it. */
    @Test
    void aStuckBiomeReportsItsLegalPartnersTouchedAndItsIllegalEdges() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = tableWithLegalPairs(List.of(A, B, C), new BiomeId[] {A, B}, new BiomeId[] {A, C});
        Map<ToroidalPos, BiomeId> pattern = everyCellHolds(grid, B);
        pattern.put(new ToroidalPos(0, 0), A);
        pattern.put(new ToroidalPos(1, 0), C);

        String section = AdjacencyReport.of(table, grid, pattern);

        assertTrue(section.contains(B + ": 1 legal partners, 1 touched, 5 illegal edges, absent legal partners none"), section);
    }

    @Test
    void aLegalPartnerNotInThePatternIsCountedAsAbsent() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = tableWithLegalPairs(List.of(A, B, C), new BiomeId[] {A, B});
        Map<ToroidalPos, BiomeId> pattern = everyCellHolds(grid, B);
        pattern.put(new ToroidalPos(1, 0), C);

        String section = AdjacencyReport.of(table, grid, pattern);

        assertTrue(section.contains(B + ": 1 legal partners, 0 touched, 6 illegal edges, absent legal partners 1 [" + A + "]"), section);
    }

    @Test
    void aPatternWithNoStuckBiomeGivesNoSection() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(300, 700, A, B, C);

        assertEquals("", AdjacencyReport.of(table, grid, threeColoring(grid, A, B, C)));
    }

    private static Map<ToroidalPos, BiomeId> everyCellHolds(ToroidalGrid grid, BiomeId biome) {
        Map<ToroidalPos, BiomeId> pattern = new HashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            pattern.put(pos, biome);
        }
        return pattern;
    }
}
