package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

class SizeSlackReportTest {

    private static final int ATOM_WIDTH_BLOCKS = 300;

    /** Every pair of biomes is illegal: each biome present in a pattern is then stuck on its neighbors. */
    private static RawRuleTable tableWithNoLegalPairs(int minWidthBlocks, int maxWidthBlocks, List<BiomeId> biomes) {
        List<NodeData> nodes = new ArrayList<>();
        for (BiomeId biome : biomes) {
            nodes.add(new NodeData(biome.namespace(), biome.path(), minWidthBlocks, maxWidthBlocks, Integer.MAX_VALUE, ""));
        }
        return RawRuleTable.from(new BiomeGraphData(nodes, List.of()));
    }

    private static ClusterSizeModel model(RawRuleTable table) {
        return new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, 3, 13, 2));
    }

    @Test
    void theMostSqueezedStuckBiomeComesFirstAndAMissingBiomeIsNamed() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = tableWithNoLegalPairs(500, 600, List.of(A, B, C));
        Map<ToroidalPos, BiomeId> pattern = everyCellHolds(grid, B);
        pattern.put(new ToroidalPos(0, 0), A);

        String report = SizeSlackReport.of(table, grid, model(table), pattern);

        String[] lines = report.trim().split("\n");
        assertEquals(3, lines.length, report);
        assertTrue(lines[0].trim().startsWith(B + ": slack -11"), report);
        assertTrue(report.contains(C + ": MISSING"), report);
        assertTrue(report.contains(A + ": slack -2 "), report);
    }

    /** A and B sit exactly on a range edge (slack 0); A's range is narrower, so A is listed first. C holds 10 cells against a 1..5 range, so it is the most squeezed. */
    @Test
    void aTieOnSlackGoesToTheNarrowerRange() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        RawRuleTable table = RawRuleTable.from(new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 300, 300, Integer.MAX_VALUE, ""),
                new NodeData(B.namespace(), B.path(), 300, 700, Integer.MAX_VALUE, ""),
                new NodeData(C.namespace(), C.path(), 300, 700, Integer.MAX_VALUE, "")
            ),
            List.of()
        ));
        Map<ToroidalPos, BiomeId> pattern = everyCellHolds(grid, C);
        for (ToroidalPos pos : List.of(new ToroidalPos(0, 0), new ToroidalPos(1, 0), new ToroidalPos(2, 0), new ToroidalPos(3, 0), new ToroidalPos(0, 1))) {
            pattern.put(pos, B);
        }
        pattern.put(new ToroidalPos(1, 1), A);

        String report = SizeSlackReport.of(table, grid, model(table), pattern);

        assertTrue(report.indexOf(C + ": slack") < report.indexOf(A + ": slack"), report);
        assertTrue(report.indexOf(A + ": slack") < report.indexOf(B + ": slack"), report);
    }

    @Test
    void onlyTheFirstFifteenStuckBiomesAreListedAndTheRestAreCounted() {
        ToroidalGrid grid = new ToroidalGrid(4, 4);
        List<BiomeId> biomes = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            biomes.add(new BiomeId("test", "many" + index));
        }
        RawRuleTable table = tableWithNoLegalPairs(300, 700, biomes);
        Map<ToroidalPos, BiomeId> pattern = new HashMap<>();
        List<ToroidalPos> positions = grid.allPositionsInFillOrder();
        for (int index = 0; index < positions.size(); index++) {
            pattern.put(positions.get(index), biomes.get(index));
        }

        String report = SizeSlackReport.of(table, grid, model(table), pattern);

        assertEquals(SizeSlackReport.LINES_SHOWN + 1, report.trim().split("\n").length, report);
        assertTrue(report.contains("... and 2 more such biomes"), report);
    }

    @Test
    void aPatternWithNoIllegalEdgeAndNoMissingBiomeSaysSo() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(300, 700, A, B, C);

        String report = SizeSlackReport.of(table, grid, model(table), threeColoring(grid, A, B, C));

        assertEquals("\n  no biome is on an illegal edge or missing", report);
    }

    private static Map<ToroidalPos, BiomeId> everyCellHolds(ToroidalGrid grid, BiomeId biome) {
        Map<ToroidalPos, BiomeId> pattern = new HashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            pattern.put(pos, biome);
        }
        return pattern;
    }
}
