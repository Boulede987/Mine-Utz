package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PatchSizeEnforcerTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId BADLANDS = id("badlands");
    private static final BiomeId OCEAN = id("ocean");
    private static final BiomeId FOREST = id("forest");
    private static final BiomeId PLAINS = id("plains");

    /**
     * Desert: only savanna/badlands/plains. Ocean: only forest/plains.
     * Everything else falls to default-deny. Plains is the universal filler.
     */
    private static BiomeGraph richGraph() {
        return new BiomeGraph(
            List.of(
                node("desert", 200, 300, false),
                node("savanna", 200, 300, false),
                node("badlands", 150, 250, false),
                node("ocean", 200, 300, false),
                node("forest", 150, 250, false),
                node("plains", 100, 200, true)
            ),
            List.of(
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(DESERT, BADLANDS, 10),
                AdjacencyRule.allow(OCEAN, FOREST, 10),
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, BADLANDS, 10),
                AdjacencyRule.allow(PLAINS, OCEAN, 10),
                AdjacencyRule.allow(PLAINS, FOREST, 10)
            )
        );
    }

    @Test
    void mergesUndersizedPatchIntoItsLargestLegalNeighbor() {
        BiomeGraph graph = richGraph();
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        // Desert's minWidthBlocks (200) maps to a 2-cell minimum at this grid's
        // cell size; a lone 1-cell desert patch is undersized.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, SAVANNA);
        }
        MacroCellPos undersizedCell = new MacroCellPos(3, 3);
        assignment.put(undersizedCell, DESERT);

        PatchSizeEnforcer.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        assertEquals(SAVANNA, assignment.get(undersizedCell), "1-cell desert patch is under its 2-cell minimum and should be absorbed");
    }

    @Test
    void fallsBackToFillerWhenNoNeighborIsLegal() {
        BiomeGraph graph = richGraph();
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        // 1-cell desert patch bordering both ocean and badlands, which aren't
        // legal next to each other (no rule between them, default-deny).
        // Neither can legally absorb the patch without also touching the other.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, OCEAN);
        }
        MacroCellPos undersizedCell = new MacroCellPos(3, 3);
        MacroCellPos badlandsNeighbor = new MacroCellPos(3, 2);
        assignment.put(undersizedCell, DESERT);
        assignment.put(badlandsNeighbor, BADLANDS);

        PatchSizeEnforcer.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        assertEquals(PLAINS, assignment.get(undersizedCell), "no legal neighbor to merge into should fall back to the universal filler");
    }

    @Test
    void leavesUndersizedFillerPatchesAlone() {
        // Filler's own declared minimum is deliberately large here so a 1-cell
        // filler patch really is undersized by the same test that would trigger
        // a merge for any other biome - the point is that it's exempt anyway.
        BiomeId filler = id("filler");
        BiomeId savanna = id("savanna");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("filler", 1000, 1000, true), node("savanna", 100, 200, false)),
            List.of(AdjacencyRule.allow(filler, savanna, 10))
        );
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, savanna);
        }
        MacroCellPos fillerCell = new MacroCellPos(3, 3);
        assignment.put(fillerCell, filler);

        PatchSizeEnforcer.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        assertEquals(filler, assignment.get(fillerCell), "universal-filler patches are the fallback of last resort, not something to merge away");
    }
}
