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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeparatorWidthEnforcerTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(128, 12);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final BiomeId OCEAN = id("ocean");
    private static final BiomeId BADLANDS = id("badlands");
    private static final BiomeId PLAINS = id("plains");

    private static Set<MacroCellPos> regionCellSet() {
        return Set.copyOf(GRID.cellsInRegion(REGION, 0L));
    }

    private static BiomeGraph graph() {
        return new BiomeGraph(
            List.of(node("ocean", 1, 900), node("badlands", 1, 900), node("plains", 1, 900)),
            // No ocean/badlands rule: default-deny, mutually incompatible - plains is the required separator.
            List.of(AdjacencyRule.allow(PLAINS, OCEAN, 10), AdjacencyRule.allow(PLAINS, BADLANDS, 10))
        );
    }

    /**
     * Reproduces the real-data shape (seed 42): a plains separator between mutually-
     * incompatible ocean/badlands runs 3-wide everywhere except z=5, where it narrows
     * to exactly 2 - the exact defect SeamWidener's own aggregate cell-count target
     * can satisfy while still leaving individual rows this thin.
     */
    @Test
    void widensARowThatsThinnerThanTheRestOfAnOtherwiseWideSeparator() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 12; z++) {
            int plainsStart = (z == 5) ? 4 : 3; // ocean encroaches one extra column only at z=5
            for (int x = 0; x < plainsStart; x++) {
                assignment.put(new MacroCellPos(x, z), OCEAN);
            }
            for (int x = plainsStart; x < plainsStart + 3; x++) {
                assignment.put(new MacroCellPos(x, z), PLAINS);
            }
            for (int x = plainsStart + 3; x < 12; x++) {
                assignment.put(new MacroCellPos(x, z), BADLANDS);
            }
        }

        SeparatorWidthEnforcer.enforceMinimumSeparatorWidth(assignment, regionCellSet(), GRID, graph());

        int widthAtRow5 = (int) assignment.entrySet().stream()
            .filter(e -> e.getKey().z() == 5 && PLAINS.equals(e.getValue()))
            .count();
        assertTrue(widthAtRow5 >= 3, "row z=5 should have been widened to at least 3 cells, was " + widthAtRow5);
    }

    /** A separator pinched to exactly 1 cell wide - PinchResolver can't fix this (either give-away recreates the ocean/badlands illegal adjacency), only widening can. */
    @Test
    void widensAOneWideSeparatorPinch() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 12; z++) {
            int plainsStart = (z == 5) ? 4 : 3;
            int plainsWidth = (z == 5) ? 1 : 3;
            for (int x = 0; x < plainsStart; x++) {
                assignment.put(new MacroCellPos(x, z), OCEAN);
            }
            for (int x = plainsStart; x < plainsStart + plainsWidth; x++) {
                assignment.put(new MacroCellPos(x, z), PLAINS);
            }
            for (int x = plainsStart + plainsWidth; x < 12; x++) {
                assignment.put(new MacroCellPos(x, z), BADLANDS);
            }
        }

        SeparatorWidthEnforcer.enforceMinimumSeparatorWidth(assignment, regionCellSet(), GRID, graph());

        int widthAtRow5 = (int) assignment.entrySet().stream()
            .filter(e -> e.getKey().z() == 5 && PLAINS.equals(e.getValue()))
            .count();
        assertTrue(widthAtRow5 >= 3, "the 1-wide pinch row should have been widened to at least 3 cells, was " + widthAtRow5);
        for (MacroCellPos cell : regionCellSet()) {
            BiomeId biome = assignment.get(cell);
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (biome != null && neighborBiome != null) {
                    assertTrue(
                        biome.equals(neighborBiome) || graph().isAllowed(biome, neighborBiome),
                        biome + " at " + cell + " ended up illegally next to " + neighborBiome + " at " + neighbor
                    );
                }
            }
        }
    }

    /**
     * Ordinary blob boundary between two MUTUALLY LEGAL biomes tapering to 2 cells
     * wide near its own tip - not a required separator (ocean/plains have a real
     * AdjacencyRule), so must be left completely alone even though it's thin.
     */
    @Test
    void leavesOrdinaryBlobTaperingAloneEvenWhenThin() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 12; z++) {
            int oceanWidth = (z == 5 || z == 6) ? 2 : 4; // ocean's own tip naturally narrows here
            for (int x = 0; x < oceanWidth; x++) {
                assignment.put(new MacroCellPos(x, z), OCEAN);
            }
            for (int x = oceanWidth; x < 12; x++) {
                assignment.put(new MacroCellPos(x, z), PLAINS);
            }
        }
        Map<MacroCellPos, BiomeId> before = new HashMap<>(assignment);

        BiomeGraph graph = new BiomeGraph(
            List.of(node("ocean", 1, 900), node("plains", 1, 900)),
            List.of(AdjacencyRule.allow(OCEAN, PLAINS, 10))
        );

        SeparatorWidthEnforcer.enforceMinimumSeparatorWidth(assignment, regionCellSet(), GRID, graph);

        assertEquals(before, assignment, "ocean/plains are mutually legal - this is ordinary tapering, not a required separator, must be left alone");
    }
}
