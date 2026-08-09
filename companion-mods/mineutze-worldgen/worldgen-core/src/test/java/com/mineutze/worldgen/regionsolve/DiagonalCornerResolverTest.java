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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DiagonalCornerResolverTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(128, 6);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final BiomeId DESERT = id("desert");
    private static final BiomeId FOREST = id("forest");
    private static final BiomeId PLAINS = id("plains");
    private static final BiomeId SAVANNA = id("savanna");

    private static Set<MacroCellPos> regionCellSet() {
        return Set.copyOf(GRID.cellsInRegion(REGION, 0L));
    }

    /**
     * Reproduces the real-data shape (seed 42): DESERT and FOREST have no
     * AdjacencyRule (default-deny), touch only at a corner, and neither
     * off-diagonal cell is DESERT or FOREST (no orthogonal contact either -
     * this is a pure corner collision). PLAINS/SAVANNA are legal next to
     * everything, so either cell can legally give itself away.
     */
    @Test
    void resolvesACornerCollisionByGivingAwayOneOfTheTwoCells() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(0, -1), DESERT); // gives DESERT a 2-cell component, so cellA isn't a lone patch at its own floor
        assignment.put(new MacroCellPos(1, 1), FOREST);
        assignment.put(new MacroCellPos(1, 2), FOREST); // same reason for FOREST
        assignment.put(new MacroCellPos(1, 0), SAVANNA);
        assignment.put(new MacroCellPos(0, 1), SAVANNA);
        assignment.put(new MacroCellPos(-1, 0), PLAINS);

        BiomeGraph graph = new BiomeGraph(
            List.of(node("desert", 1, 900), node("forest", 1, 900), node("plains", 1, 900), node("savanna", 1, 900)),
            List.of(
                AdjacencyRule.allow(DESERT, PLAINS, 10),
                AdjacencyRule.allow(DESERT, SAVANNA, 10),
                AdjacencyRule.allow(FOREST, PLAINS, 10),
                AdjacencyRule.allow(FOREST, SAVANNA, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10)
                // No DESERT/FOREST rule: default-deny, mutually incompatible (same class as real badlands/forest).
            )
        );

        DiagonalCornerResolver.resolveDiagonalCollisions(assignment, regionCellSet(), GRID, graph);

        BiomeId resolvedA = assignment.get(new MacroCellPos(0, 0));
        BiomeId resolvedB = assignment.get(new MacroCellPos(1, 1));
        assertNotEquals(FOREST, resolvedA, "either the DESERT cell or the FOREST cell must have moved");
        boolean stillCollides = !resolvedA.equals(resolvedB) && !graph.isAllowed(resolvedA, resolvedB);
        assertEquals(false, stillCollides, "the corner collision must be gone: " + resolvedA + " / " + resolvedB);
    }

    @Test
    void leavesNonCollidingCellsAlone() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                assignment.put(new MacroCellPos(x, z), DESERT);
            }
        }
        for (int x = 3; x < 6; x++) {
            for (int z = 0; z < 3; z++) {
                assignment.put(new MacroCellPos(x, z), FOREST);
            }
        }
        Map<MacroCellPos, BiomeId> before = new HashMap<>(assignment);

        BiomeGraph graph = new BiomeGraph(
            List.of(node("desert", 1, 900), node("forest", 1, 900), node("plains", 1, 900)),
            List.of(AdjacencyRule.allow(DESERT, PLAINS, 10), AdjacencyRule.allow(FOREST, PLAINS, 10))
        );

        DiagonalCornerResolver.resolveDiagonalCollisions(assignment, regionCellSet(), GRID, graph);

        assertEquals(before, assignment, "DESERT and FOREST touch orthogonally here (already an ordinary illegal-adjacency case, not this pass's job) - nothing for a corner-only fix to do");
    }

    /**
     * Same corner shape as the success case, but the only biome either cell could give
     * itself away to (SAVANNA) has no AdjacencyRule with the OTHER cell's biome either -
     * giving DESERT away to SAVANNA would still collide with FOREST, and vice versa.
     * Must be left alone, not forced.
     */
    @Test
    void leavesACollisionAloneWhenNeitherCellCanLegallyGiveItAway() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(1, 1), FOREST);
        assignment.put(new MacroCellPos(1, 0), SAVANNA);
        assignment.put(new MacroCellPos(0, 1), SAVANNA);

        BiomeGraph graph = new BiomeGraph(
            List.of(node("desert", 1, 900), node("forest", 1, 900), node("plains", 1, 900), node("savanna", 1, 900)),
            List.of(
                // Bridges so BiomeGraph's own construction-time validation is satisfied,
                // but SAVANNA has no direct rule with either DESERT or FOREST.
                AdjacencyRule.allow(PLAINS, DESERT, 10),
                AdjacencyRule.allow(PLAINS, FOREST, 10),
                AdjacencyRule.allow(PLAINS, SAVANNA, 10)
            )
        );

        DiagonalCornerResolver.resolveDiagonalCollisions(assignment, regionCellSet(), GRID, graph);

        assertEquals(DESERT, assignment.get(new MacroCellPos(0, 0)), "SAVANNA can't legally replace either cell without colliding with the other - must stay DESERT");
        assertEquals(FOREST, assignment.get(new MacroCellPos(1, 1)), "same reasoning symmetrically - must stay FOREST");
    }

    /**
     * DESERT's whole component is exactly at DESERT's own declared minimum size, and
     * FOREST is a lone single cell (already at its own floor) - giving either cell away
     * would drop its patch below its minimum. Must be left alone.
     */
    @Test
    void leavesACollisionAloneWhenGivingItAwayWouldDropTheDonorBelowItsMinimumSize() {
        MacroGridConfig grid = new MacroGridConfig(100, 6);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        // 6-cell DESERT block (2 wide x 3 tall), cellA = (1,2) is the corner cell.
        assignment.put(new MacroCellPos(0, 0), DESERT);
        assignment.put(new MacroCellPos(1, 0), DESERT);
        assignment.put(new MacroCellPos(0, 1), DESERT);
        assignment.put(new MacroCellPos(1, 1), DESERT);
        assignment.put(new MacroCellPos(0, 2), DESERT);
        assignment.put(new MacroCellPos(1, 2), DESERT); // cellA
        assignment.put(new MacroCellPos(2, 3), FOREST); // cellB, diagonal to cellA
        assignment.put(new MacroCellPos(2, 2), PLAINS); // off1
        assignment.put(new MacroCellPos(1, 3), PLAINS); // off2

        BiomeGraph graph = new BiomeGraph(
            // minWidthBlocks=245 at a 100-block cell -> cellCountForWidthBlocks = 6:
            // exactly DESERT's own component size, so losing cellA would drop it to 5.
            List.of(node("desert", 245, 900), node("forest", 1, 900), node("plains", 1, 900)),
            List.of(AdjacencyRule.allow(DESERT, PLAINS, 10), AdjacencyRule.allow(FOREST, PLAINS, 10))
        );
        assertEquals(6, grid.cellCountForWidthBlocks(245), "test setup sanity check - DESERT's component must equal its own minCells exactly");

        DiagonalCornerResolver.resolveDiagonalCollisions(assignment, Set.copyOf(grid.cellsInRegion(REGION, 0L)), grid, graph);

        assertEquals(DESERT, assignment.get(new MacroCellPos(1, 2)), "giving cellA away would drop DESERT's component below its declared minimum size - must stay DESERT");
        assertEquals(FOREST, assignment.get(new MacroCellPos(2, 3)), "FOREST is a lone cell already at its own floor - giving it away would drop it to 0 - must stay FOREST");
    }
}
