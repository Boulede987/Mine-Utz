package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct test - flagged by this session's test-coverage audit as having zero dedicated tests despite bundling the exact translation math previously duplicated across AsciiExporter/PngExporter. */
class ExportSnapshotTest {

    private static final BiomeId DESERT = new BiomeId("test", "desert");

    private static BiomeGraph graph() {
        return new BiomeGraph(List.of(new BiomeNode(DESERT, new BiomeSizeRange(100, 200))), List.of());
    }

    @Test
    void spanCellsIsRegionSpanTimesRegionSize() {
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        ExportSnapshot snapshot = new ExportSnapshot(graph(), grid, 0L, new RegionPos(0, 0), 3, Map.of(), Map.of());

        assertEquals(21, snapshot.spanCells());
    }

    @Test
    void orderedCellsCoversTheWholeSwathExactlyOnceInXThenZOrder() {
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        ExportSnapshot snapshot = new ExportSnapshot(graph(), grid, 0L, new RegionPos(0, 0), 2, Map.of(), Map.of());

        List<MacroCellPos> ordered = snapshot.orderedCells();

        assertEquals(snapshot.spanCells() * snapshot.spanCells(), ordered.size());
        assertEquals(ordered.size(), java.util.Set.copyOf(ordered).size(), "every cell must appear exactly once");
        for (int i = 1; i < ordered.size(); i++) {
            MacroCellPos previous = ordered.get(i - 1);
            MacroCellPos current = ordered.get(i);
            boolean inOrder = previous.x() < current.x() || (previous.x() == current.x() && previous.z() <= current.z());
            assertTrue(inOrder, "expected x-major then z order, broke at index " + i);
        }
    }

    @Test
    void cellAtTranslatesFromSwathLocalToRealMacroCellCoordinates() {
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        ExportSnapshot snapshot = new ExportSnapshot(graph(), grid, 0L, new RegionPos(1, 2), 3, Map.of(), Map.of());

        assertEquals(new MacroCellPos(7, 14), snapshot.cellAt(0, 0));
        assertEquals(new MacroCellPos(9, 17), snapshot.cellAt(2, 3));
    }

    @Test
    void biomeAtReturnsTheRealBiomeForAKnownCell() {
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        MacroCellPos cell = new MacroCellPos(0, 0);
        Map<MacroCellPos, BiomeId> cells = new HashMap<>(Map.of(cell, DESERT));
        ExportSnapshot snapshot = new ExportSnapshot(graph(), grid, 0L, new RegionPos(0, 0), 1, cells, Map.of());

        assertEquals(DESERT, snapshot.biomeAt(cell));
    }

    @Test
    void biomeAtThrowsForACellOutsideTheSnapshot() {
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        ExportSnapshot snapshot = new ExportSnapshot(graph(), grid, 0L, new RegionPos(0, 0), 1, Map.of(), Map.of());

        assertThrows(IllegalArgumentException.class, () -> snapshot.biomeAt(new MacroCellPos(999, 999)));
    }
}
