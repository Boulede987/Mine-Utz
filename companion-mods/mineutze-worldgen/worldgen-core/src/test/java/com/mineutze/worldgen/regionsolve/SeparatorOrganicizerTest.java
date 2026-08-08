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

class SeparatorOrganicizerTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(128, 10);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final BiomeId PLAINS = id("plains");
    private static final BiomeId OCEAN = id("ocean");
    private static final BiomeId BADLANDS = id("badlands");

    private static Set<MacroCellPos> regionCellSet() {
        return Set.copyOf(GRID.cellsInRegion(REGION));
    }

    private static BiomeGraph graph() {
        return new BiomeGraph(
            List.of(node("plains", 1, 900), node("ocean", 1, 900), node("badlands", 1, 900)),
            // No ocean/badlands rule: default-deny, mutually incompatible - plains is the required separator.
            List.of(AdjacencyRule.allow(PLAINS, OCEAN, 10), AdjacencyRule.allow(PLAINS, BADLANDS, 10))
        );
    }

    /**
     * Reproduces the real bug (seed 42, user-reported "these are lines, not blobs"):
     * a big, roughly-square PLAINS blob (6x6) with a 1-wide corridor tail sticking out
     * east, squeezed between mutually-incompatible ocean/badlands along the tail. The
     * WHOLE component's bounding box (10 wide x 6 tall) has aspect ratio 1.67 - well
     * under the OLD isElongated()'s 3.0 threshold, so the entire component (including
     * the genuinely corridor-shaped tail) used to get skipped completely. Local
     * detection must still catch and bulge the tail regardless of the blob's shape.
     */
    @Test
    void bulgesALocalCorridorTailEvenWhenTheWholeComponentIsntElongated() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                assignment.put(new MacroCellPos(x, z), PLAINS);
            }
        }
        for (int x = 6; x < 10; x++) {
            assignment.put(new MacroCellPos(x, 1), OCEAN);
            assignment.put(new MacroCellPos(x, 2), PLAINS); // 1-wide corridor tail
            assignment.put(new MacroCellPos(x, 3), BADLANDS);
        }

        SeparatorOrganicizer.organicizeThinSeparators(assignment, regionCellSet(), GRID, graph(), 42L);

        long plainsAlongTail = List.of(6, 7, 8, 9).stream()
            .mapToLong(x -> {
                int width = 0;
                width += PLAINS.equals(assignment.get(new MacroCellPos(x, 1))) ? 1 : 0;
                width += PLAINS.equals(assignment.get(new MacroCellPos(x, 2))) ? 1 : 0;
                width += PLAINS.equals(assignment.get(new MacroCellPos(x, 3))) ? 1 : 0;
                return width;
            })
            .sum();
        assertTrue(plainsAlongTail > 4, "the corridor tail should have gained cells from bulging (started at exactly 4, one per column), got " + plainsAlongTail);
    }

    /** No incompatible pair anywhere - ordinary blob, not a required separator at all, must be left alone even though it's a similar shape. */
    @Test
    void leavesAnOrdinaryElongatedPatchAloneWhenItsNotBridgingAnIncompatiblePair() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int x = 0; x < 8; x++) {
            assignment.put(new MacroCellPos(x, 0), PLAINS);
        }
        Map<MacroCellPos, BiomeId> before = new HashMap<>(assignment);

        BiomeGraph graph = new BiomeGraph(List.of(node("plains", 1, 900)), List.of());

        SeparatorOrganicizer.organicizeThinSeparators(assignment, regionCellSet(), GRID, graph, 42L);

        assertEquals(before, assignment, "no neighbors at all here, let alone an incompatible pair - nothing for this pass to do");
    }

    @Test
    void neverCreatesAnIllegalAdjacency() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                assignment.put(new MacroCellPos(x, z), PLAINS);
            }
        }
        for (int x = 6; x < 10; x++) {
            assignment.put(new MacroCellPos(x, 1), OCEAN);
            assignment.put(new MacroCellPos(x, 2), PLAINS);
            assignment.put(new MacroCellPos(x, 3), BADLANDS);
        }
        BiomeGraph graph = graph();

        SeparatorOrganicizer.organicizeThinSeparators(assignment, regionCellSet(), GRID, graph, 42L);

        for (MacroCellPos cell : regionCellSet()) {
            BiomeId biome = assignment.get(cell);
            if (biome == null) {
                continue;
            }
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null) {
                    assertTrue(
                        biome.equals(neighborBiome) || graph.isAllowed(biome, neighborBiome),
                        biome + " at " + cell + " ended up illegally next to " + neighborBiome + " at " + neighbor
                    );
                }
            }
        }
    }
}
