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
    void widensARequiredSeparatorInsteadOfMergingItAway() {
        BiomeGraph graph = richGraph();
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        // 1-cell desert patch bordering both savanna and a properly-sized (2-cell,
        // meeting its own minimum so it isn't itself merged away first) ocean patch.
        // Savanna-ocean aren't legal next to each other (no rule, default-deny), so
        // desert can't merge into either without also touching the other illegally.
        // Desert IS legal next to savanna, so it should widen into the savanna sea
        // instead of being replaced by filler.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, SAVANNA);
        }
        MacroCellPos undersizedCell = new MacroCellPos(3, 3);
        assignment.put(undersizedCell, DESERT);
        assignment.put(new MacroCellPos(3, 2), OCEAN);
        assignment.put(new MacroCellPos(3, 1), OCEAN);

        PatchSizeEnforcer.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        // Desert's minWidthBlocks (200) is a 2-cell minimum at this grid's cell size.
        long desertCells = regionCellSet.stream().filter(cell -> DESERT.equals(assignment.get(cell))).count();
        assertEquals(2, desertCells, "desert should widen to its own 2-cell minimum, not get replaced by filler");
        assertEquals(DESERT, assignment.get(undersizedCell), "the original cell keeps its own identity");
    }

    @Test
    void keepsItsOwnIdentityWhenNoLegalMergeOrWidenRoomExists() {
        BiomeGraph graph = richGraph();
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        // 1-cell desert patch entirely surrounded by ocean except one badlands cell,
        // which is itself also boxed in by ocean - no legal merge target, and no
        // legal cell to widen into either (every candidate touches ocean, which
        // desert isn't allowed next to). Best-effort widening can't do anything
        // here, but desert must still never be silently replaced by filler.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, OCEAN);
        }
        MacroCellPos undersizedCell = new MacroCellPos(3, 3);
        MacroCellPos badlandsNeighbor = new MacroCellPos(3, 2);
        assignment.put(undersizedCell, DESERT);
        assignment.put(badlandsNeighbor, BADLANDS);

        PatchSizeEnforcer.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        assertEquals(DESERT, assignment.get(undersizedCell), "no legal merge or widen target should still never silently become filler");
    }

    @Test
    void widensAnUndersizedFillerSeparatorTooNotJustNonFillerBiomes() {
        // Filler's own declared minimum is deliberately large here so a 1-cell
        // filler patch really is undersized - filler is no longer exempt from
        // enforcement, so it should widen the same as any other biome.
        BiomeId filler = id("filler");
        BiomeId savanna = id("savanna");
        BiomeId ocean = id("ocean");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("filler", 500, 500, true), node("savanna", 100, 200, false), node("ocean", 100, 200, false)),
            List.of(AdjacencyRule.allow(filler, savanna, 10), AdjacencyRule.allow(filler, ocean, 10))
        );
        MacroGridConfig grid = new MacroGridConfig(128, 7);
        RegionPos region = new RegionPos(0, 0);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(region));

        // savanna-ocean has no rule (default-deny), so a filler cell bridging them
        // can't legally merge into either - it should widen into the savanna sea.
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : regionCellSet) {
            assignment.put(cell, savanna);
        }
        MacroCellPos fillerCell = new MacroCellPos(3, 3);
        MacroCellPos oceanNeighbor = new MacroCellPos(3, 2);
        assignment.put(fillerCell, filler);
        assignment.put(oceanNeighbor, ocean);

        PatchSizeEnforcer.enforceMinimumPatchSize(assignment, regionCellSet, grid, graph);

        long fillerCells = regionCellSet.stream().filter(cell -> filler.equals(assignment.get(cell))).count();
        assertEquals(15, fillerCells, "filler's minWidthBlocks (500) is a 15-cell minimum at this grid's cell size - it should widen to it, not stay at 1 cell");
    }
}
