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

class PinchResolverTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(128, 10);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final BiomeId CORE = id("core");
    private static final BiomeId WEST_FLANK = id("west_flank");
    private static final BiomeId EAST_FLANK = id("east_flank");

    private static Set<MacroCellPos> regionCellSet() {
        return Set.copyOf(GRID.cellsInRegion(REGION));
    }

    /**
     * Reproduces the real-data shape (seed 42, macro-cell (-24,-24)): a CORE cell
     * hangs off a solid CORE block to its north, with WEST_FLANK and EAST_FLANK
     * directly on either side - a 2x2 square exists one row up (so PatchOpener's
     * own erosion test never flags this cell), but the cell itself is exactly
     * 1-wide east-west. All three biomes mutually legal, so either flank could
     * legally absorb it.
     */
    @Test
    void resolvesATabPinchByGivingItToALegalFlankingBiome() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        // Solid 2x2 CORE block at (2,0)-(3,1).
        assignment.put(new MacroCellPos(2, 0), CORE);
        assignment.put(new MacroCellPos(3, 0), CORE);
        assignment.put(new MacroCellPos(2, 1), CORE);
        assignment.put(new MacroCellPos(3, 1), CORE);
        // The pinched tab, one row south, backed only by the block above.
        assignment.put(new MacroCellPos(2, 2), CORE);
        assignment.put(new MacroCellPos(1, 2), WEST_FLANK);
        assignment.put(new MacroCellPos(3, 2), EAST_FLANK);

        BiomeGraph graph = new BiomeGraph(
            List.of(node("core", 1, 900), node("west_flank", 1, 900), node("east_flank", 1, 900)),
            List.of(
                AdjacencyRule.allow(CORE, WEST_FLANK, 10),
                AdjacencyRule.allow(CORE, EAST_FLANK, 10),
                AdjacencyRule.allow(WEST_FLANK, EAST_FLANK, 10)
            )
        );

        PinchResolver.resolvePinchPoints(assignment, regionCellSet(), GRID, graph);

        assertNotEquals(CORE, assignment.get(new MacroCellPos(2, 2)), "the tab should have been given to a legal flanking biome, not left as a 1-wide CORE sliver");
    }

    @Test
    void leavesAGenuineBlobBoundaryCellAlone() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                assignment.put(new MacroCellPos(x, z), CORE);
            }
        }
        for (int x = 4; x < 6; x++) {
            for (int z = 0; z < 4; z++) {
                assignment.put(new MacroCellPos(x, z), EAST_FLANK);
            }
        }
        Map<MacroCellPos, BiomeId> before = new HashMap<>(assignment);

        BiomeGraph graph = new BiomeGraph(
            List.of(node("core", 1, 900), node("east_flank", 1, 900)),
            List.of(AdjacencyRule.allow(CORE, EAST_FLANK, 10))
        );

        PinchResolver.resolvePinchPoints(assignment, regionCellSet(), GRID, graph);

        assertEquals(before, assignment, "every cell here has a same-biome neighbor on both axes somewhere (both patches are 2+ wide throughout) - nothing is actually pinched");
    }

    /**
     * Same tab shape as the success case, but WEST_FLANK and EAST_FLANK have no
     * AdjacencyRule between them - whichever one absorbs the tab would end up
     * touching the other one directly (they're both still neighbors of the tab's
     * own position), so neither candidate is legal. Must be left alone, not forced.
     */
    @Test
    void leavesAPinchAloneWhenNeitherFlankCanLegallyAbsorbIt() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(2, 0), CORE);
        assignment.put(new MacroCellPos(3, 0), CORE);
        assignment.put(new MacroCellPos(2, 1), CORE);
        assignment.put(new MacroCellPos(3, 1), CORE);
        assignment.put(new MacroCellPos(2, 2), CORE);
        assignment.put(new MacroCellPos(1, 2), WEST_FLANK);
        assignment.put(new MacroCellPos(3, 2), EAST_FLANK);

        BiomeGraph graph = new BiomeGraph(
            List.of(node("core", 1, 900), node("west_flank", 1, 900), node("east_flank", 1, 900)),
            // No WEST_FLANK/EAST_FLANK rule: default-deny, mutually incompatible.
            List.of(AdjacencyRule.allow(CORE, WEST_FLANK, 10), AdjacencyRule.allow(CORE, EAST_FLANK, 10))
        );

        PinchResolver.resolvePinchPoints(assignment, regionCellSet(), GRID, graph);

        assertEquals(CORE, assignment.get(new MacroCellPos(2, 2)), "neither flank can legally absorb the tab without an illegal adjacency - must stay CORE, not be forced");
    }

    /**
     * CORE's whole component (the 2x2 block plus the tab) is exactly at CORE's own
     * declared minimum size - giving the tab away would drop the remaining block
     * below it. Must be left alone, mirroring SeparatorWidener/PatchSizeEnforcer's
     * own "never shrink a donor below its minimum" guarantee.
     */
    @Test
    void leavesAPinchAloneWhenGivingItAwayWouldDropTheDonorBelowItsMinimumSize() {
        MacroGridConfig grid = new MacroGridConfig(100, 10);
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        assignment.put(new MacroCellPos(2, 0), CORE);
        assignment.put(new MacroCellPos(3, 0), CORE);
        assignment.put(new MacroCellPos(2, 1), CORE);
        assignment.put(new MacroCellPos(3, 1), CORE);
        assignment.put(new MacroCellPos(2, 2), CORE); // 5th CORE cell = the pinched tab
        assignment.put(new MacroCellPos(1, 2), WEST_FLANK);
        assignment.put(new MacroCellPos(3, 2), EAST_FLANK);

        BiomeGraph graph = new BiomeGraph(
            // minWidthBlocks=245 at a 100-block cell -> cellCountForWidthBlocks = 6:
            // exactly the component's own total size, so losing the tab would drop it to 5.
            List.of(node("core", 245, 900), node("west_flank", 1, 900), node("east_flank", 1, 900)),
            List.of(
                AdjacencyRule.allow(CORE, WEST_FLANK, 10),
                AdjacencyRule.allow(CORE, EAST_FLANK, 10),
                AdjacencyRule.allow(WEST_FLANK, EAST_FLANK, 10)
            )
        );
        assertEquals(6, grid.cellCountForWidthBlocks(245), "test setup sanity check - CORE's component must equal its own minCells exactly");

        PinchResolver.resolvePinchPoints(assignment, Set.copyOf(grid.cellsInRegion(REGION)), grid, graph);

        assertEquals(CORE, assignment.get(new MacroCellPos(2, 2)), "giving away the tab would drop CORE's component below its declared minimum size - must stay CORE");
    }
}
