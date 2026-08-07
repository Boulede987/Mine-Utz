package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PatchOpenerTest {

    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final BiomeId F = id("f");
    private static final BiomeId P = id("p");
    private static final long WORLD_SEED = 3L;
    private static final GrowthCohesionProfile COMPACT = new GrowthCohesionProfile(2, 0, 0);

    /**
     * Reproduces the exact in-game shape reported: a solid 3x3 forest block with a
     * 4-cell "peninsula" trailing off it (1,2)-(1,3)-(1,4)-(2,4). Every peninsula cell
     * has Moore-neighborhood same-biome count >= 2 (the old, insufficient check) but
     * none belongs to a full 2x2 forest square, so all 4 erode. (1,2) and (1,3) each
     * have EXACTLY 2 Moore-neighbors of support from the solid block (one orthogonal
     * contact plus its one diagonal) - re-dilation's stricter cohesion (opened
     * threshold + 1) must refuse to reclaim them on that alone; a real ordinary blob
     * boundary scores 3. This is the exact case a live-game export caught after the
     * first version of this class shipped: those two cells regrew right back even
     * though the far tip correctly stayed gone.
     */
    @Test
    void trimsALongOneWideProtrusionButKeepsTheSolidCore() {
        MacroGridConfig grid = new MacroGridConfig(128, 5);
        BiomeGraph graph = new BiomeGraph(
            List.of(node("f", 100, 900, COMPACT), node("p", 100, 900)),
            List.of(AdjacencyRule.allow(P, F, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        List<MacroCellPos> forestCells = List.of(
            new MacroCellPos(2, 0), new MacroCellPos(3, 0), new MacroCellPos(4, 0),
            new MacroCellPos(2, 1), new MacroCellPos(3, 1), new MacroCellPos(4, 1),
            new MacroCellPos(1, 2), new MacroCellPos(2, 2), new MacroCellPos(3, 2), new MacroCellPos(4, 2),
            new MacroCellPos(1, 3),
            new MacroCellPos(1, 4), new MacroCellPos(2, 4)
        );
        for (MacroCellPos cell : grid.cellsInRegion(REGION)) {
            assignment.put(cell, forestCells.contains(cell) ? F : P);
        }
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(REGION));

        PatchOpener.openThinProtrusions(assignment, regionCellSet, grid, graph, WORLD_SEED);

        assertEquals(F, assignment.get(new MacroCellPos(3, 1)), "interior of the solid block must survive untouched");
        assertNotEquals(F, assignment.get(new MacroCellPos(1, 4)), "far tip of the 4-long protrusion must not survive or regrow");
        assertNotEquals(F, assignment.get(new MacroCellPos(2, 4)), "far tip of the 4-long protrusion must not survive or regrow");
        assertNotEquals(F, assignment.get(new MacroCellPos(1, 2)), "a bare 2-neighbor reclaim must not regrow a dangling stub - this exact cell did before the stricter re-dilation fix");
        assertNotEquals(F, assignment.get(new MacroCellPos(1, 3)), "a bare 2-neighbor reclaim must not regrow a dangling stub - this exact cell did before the stricter re-dilation fix");
    }

    @Test
    void aSolidBlockIsUntouched() {
        MacroGridConfig grid = new MacroGridConfig(128, 6);
        BiomeGraph graph = new BiomeGraph(
            List.of(node("f", 100, 900, COMPACT), node("p", 100, 900)),
            List.of(AdjacencyRule.allow(P, F, 10))
        );
        Set<MacroCellPos> forestBlock = Set.of(
            new MacroCellPos(1, 1), new MacroCellPos(2, 1), new MacroCellPos(3, 1), new MacroCellPos(4, 1),
            new MacroCellPos(1, 2), new MacroCellPos(2, 2), new MacroCellPos(3, 2), new MacroCellPos(4, 2),
            new MacroCellPos(1, 3), new MacroCellPos(2, 3), new MacroCellPos(3, 3), new MacroCellPos(4, 3),
            new MacroCellPos(1, 4), new MacroCellPos(2, 4), new MacroCellPos(3, 4), new MacroCellPos(4, 4)
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : grid.cellsInRegion(REGION)) {
            assignment.put(cell, forestBlock.contains(cell) ? F : P);
        }
        Map<MacroCellPos, BiomeId> before = Map.copyOf(assignment);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(REGION));

        PatchOpener.openThinProtrusions(assignment, regionCellSet, grid, graph, WORLD_SEED);

        assertEquals(before, assignment, "a solid rectangle has full 2x2 support everywhere - opening must be a no-op");
    }

    @Test
    void neverAssignsTwoMutuallyDisallowedBiomesNextToEachOther() {
        MacroGridConfig grid = new MacroGridConfig(128, 8);
        BiomeId a = id("a");
        BiomeId b = id("b");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900, COMPACT), node("b", 100, 900, COMPACT), node("p", 100, 900)),
            // No a/b rule: default-deny, mutually incompatible.
            List.of(AdjacencyRule.allow(P, a, 10), AdjacencyRule.allow(P, b, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 8; z++) {
            assignment.put(new MacroCellPos(0, z), a);
            assignment.put(new MacroCellPos(1, z), a);
            assignment.put(new MacroCellPos(6, z), b);
            assignment.put(new MacroCellPos(7, z), b);
        }
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(REGION));
        // PatchOpener's only real caller (RegionSolver.cleanUp) always runs GapFiller
        // immediately before it, so every regionCellSet cell is already assigned by the
        // time PatchOpener sees it - mirror that here rather than leaving the middle
        // columns null, which PatchOpener (correctly) doesn't defensively re-cover.
        GapFiller.fillGaps(assignment, regionCellSet, grid, graph, WORLD_SEED);

        PatchOpener.openThinProtrusions(assignment, regionCellSet, grid, graph, WORLD_SEED);

        for (MacroCellPos cell : regionCellSet) {
            BiomeId biome = assignment.get(cell);
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !neighborBiome.equals(biome)) {
                    assertNotEquals(
                        List.of(a, b), normalizedPair(biome, neighborBiome),
                        biome + " at " + cell + " ended up next to disallowed " + neighborBiome + " at " + neighbor
                    );
                }
            }
        }
    }

    private static List<BiomeId> normalizedPair(BiomeId x, BiomeId y) {
        return x.compareTo(y) <= 0 ? List.of(x, y) : List.of(y, x);
    }

    @Test
    void aFullyErodedPatchStillLeavesEveryCellAssigned() {
        MacroGridConfig grid = new MacroGridConfig(128, 5);
        BiomeGraph graph = new BiomeGraph(
            List.of(node("f", 100, 900, COMPACT), node("p", 100, 900)),
            List.of(AdjacencyRule.allow(P, F, 10))
        );
        // A pure 1-wide plus-shape: no cell is ever part of a full 2x2 square, so the
        // entire patch erodes to nothing.
        Set<MacroCellPos> line = Set.of(new MacroCellPos(2, 1), new MacroCellPos(2, 2), new MacroCellPos(2, 3), new MacroCellPos(1, 2), new MacroCellPos(3, 2));
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : grid.cellsInRegion(REGION)) {
            assignment.put(cell, line.contains(cell) ? F : P);
        }
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(REGION));

        PatchOpener.openThinProtrusions(assignment, regionCellSet, grid, graph, WORLD_SEED);

        assertEquals(regionCellSet.size(), assignment.size(), "every cell must still end up assigned after erosion + re-dilation");
    }

    @Test
    void unrestrictedBiomeIsExemptFromOpening() {
        MacroGridConfig grid = new MacroGridConfig(128, 5);
        BiomeGraph graph = new BiomeGraph(
            List.of(node("f", 100, 900, GrowthCohesionProfile.UNRESTRICTED), node("p", 100, 900)),
            List.of(AdjacencyRule.allow(P, F, 10))
        );
        Set<MacroCellPos> line = Set.of(new MacroCellPos(2, 1), new MacroCellPos(2, 2), new MacroCellPos(2, 3), new MacroCellPos(1, 2), new MacroCellPos(3, 2));
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (MacroCellPos cell : grid.cellsInRegion(REGION)) {
            assignment.put(cell, line.contains(cell) ? F : P);
        }
        Map<MacroCellPos, BiomeId> before = Map.copyOf(assignment);
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(REGION));

        PatchOpener.openThinProtrusions(assignment, regionCellSet, grid, graph, WORLD_SEED);

        assertEquals(before, assignment, "UNRESTRICTED biome opted out of chunkiness - opening must leave it alone");
    }
}
