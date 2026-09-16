package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.PeriodicRowLayout.Cell;
import com.mineutze.worldgen.coherence.RowFeasibility.WidthInterval;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Small hand-built synthetic graph, not the real catalog - this class's job is to prove
 * the WIDTH ARITHMETIC itself is correct, independent of whatever the real 66-biome
 * catalog happens to do.
 */
class RowFeasibilityTest {

    private static final BiomeId A = new BiomeId("test", "a"); // 500-800
    private static final BiomeId B = new BiomeId("test", "b"); // 300-700
    private static final double ROW_HEIGHT_BLOCKS = 100.0;

    private static RawRuleTable table() {
        BiomeGraphData data = new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(B.namespace(), B.path(), 300, 700, Integer.MAX_VALUE, List.of())
            ),
            List.of(new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10))
        );
        return RawRuleTable.from(data);
    }

    @Test
    void achievableIntervalSumsEachBiomesOwnMinMax() {
        WidthInterval interval = RowFeasibility.achievableInterval(List.of(A, B), table(), ROW_HEIGHT_BLOCKS);
        // A: 500-800/100 = 5-8, B: 300-700/100 = 3-7, summed: 8-15
        assertEquals(8.0, interval.lowBlocks(), 1e-9);
        assertEquals(15.0, interval.highBlocks(), 1e-9);
    }

    @Test
    void fitsIsTrueStrictlyInsideAndFalseClearlyOutside() {
        WidthInterval interval = new WidthInterval(8.0, 15.0);
        assertTrue(RowFeasibility.fits(10.0, interval));
        assertTrue(RowFeasibility.fits(8.0, interval)); // exact lower bound
        assertTrue(RowFeasibility.fits(15.0, interval)); // exact upper bound
        assertFalse(RowFeasibility.fits(7.0, interval));
        assertFalse(RowFeasibility.fits(16.0, interval));
    }

    @Test
    void distributeWidthsSumsToExactlyTheCircumference() {
        List<Cell> cells = RowFeasibility.distributeWidths(List.of(A, B), table(), ROW_HEIGHT_BLOCKS, 10.0);
        double totalWidth = cells.stream().mapToDouble(Cell::widthBlocks).sum();
        assertEquals(10.0, totalWidth, 1e-9);
    }

    @Test
    void distributeWidthsKeepsEveryCellInsideItsOwnRange() {
        List<Cell> cells = RowFeasibility.distributeWidths(List.of(A, B), table(), ROW_HEIGHT_BLOCKS, 10.0);
        assertEquals(A, cells.get(0).biomeId());
        assertTrue(cells.get(0).widthBlocks() >= 5.0 && cells.get(0).widthBlocks() <= 8.0);
        assertEquals(B, cells.get(1).biomeId());
        assertTrue(cells.get(1).widthBlocks() >= 3.0 && cells.get(1).widthBlocks() <= 7.0);
    }

    @Test
    void distributeWidthsPositionsAreCumulative() {
        List<Cell> cells = RowFeasibility.distributeWidths(List.of(A, B), table(), ROW_HEIGHT_BLOCKS, 10.0);
        assertEquals(0.0, cells.get(0).startBlocks(), 1e-9);
        assertEquals(cells.get(0).widthBlocks(), cells.get(1).startBlocks(), 1e-9);
    }

    @Test
    void distributeWidthsAtExactLowBoundGivesEveryCellItsOwnMinimum() {
        List<Cell> cells = RowFeasibility.distributeWidths(List.of(A, B), table(), ROW_HEIGHT_BLOCKS, 8.0);
        assertEquals(5.0, cells.get(0).widthBlocks(), 1e-9);
        assertEquals(3.0, cells.get(1).widthBlocks(), 1e-9);
    }

    @Test
    void distributeWidthsRejectsACircumferenceThatDoesNotFit() {
        assertThrows(IllegalArgumentException.class, () -> RowFeasibility.distributeWidths(List.of(A, B), table(), ROW_HEIGHT_BLOCKS, 100.0));
    }
}
