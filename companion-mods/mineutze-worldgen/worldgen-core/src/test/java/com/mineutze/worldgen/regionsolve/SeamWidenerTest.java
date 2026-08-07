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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeamWidenerTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(128, 10);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final BiomeId OCEAN = id("ocean");
    private static final BiomeId BADLANDS = id("badlands");
    private static final BiomeId PLAINS = id("plains");

    private static BiomeGraph graph() {
        return new BiomeGraph(
            List.of(node("ocean", 100, 900), node("badlands", 100, 900), node("plains", 100, 900)),
            // No ocean/badlands rule: default-deny, mutually incompatible.
            List.of(AdjacencyRule.allow(PLAINS, OCEAN, 10), AdjacencyRule.allow(PLAINS, BADLANDS, 10))
        );
    }

    /**
     * Reproduces the reported shape: ocean and badlands columns with a plains buffer
     * between them that's 2-wide everywhere except exactly at z=5, where it narrows to
     * 1 cell (ocean encroaches one extra column just at that row). Every pinch cell
     * still individually has plenty of same-biome Moore support from the wider buffer
     * immediately above/below - PatchOpener's own check would leave it alone - so this
     * specifically exercises the "locally pinched inside an otherwise fine patch"
     * case SeamWidener exists for.
     */
    @Test
    void widensALocallyPinchedSeamBetweenIncompatibleBiomes() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 10; z++) {
            int oceanEnd = (z == 5) ? 4 : 3; // encroach one extra column only at the pinch row
            for (int x = 0; x <= oceanEnd; x++) {
                assignment.put(new MacroCellPos(x, z), OCEAN);
            }
            for (int x = oceanEnd + 1; x <= 5; x++) {
                assignment.put(new MacroCellPos(x, z), PLAINS);
            }
            for (int x = 6; x < 10; x++) {
                assignment.put(new MacroCellPos(x, z), BADLANDS);
            }
        }
        Set<MacroCellPos> regionCellSet = Set.copyOf(GRID.cellsInRegion(REGION));
        BiomeGraph graph = graph();

        SeamWidener.widenNarrowSeams(assignment, regionCellSet, graph);

        List<Set<MacroCellPos>> plainsComponents = ConnectedComponents.find(assignment, regionCellSet).stream()
            .filter(component -> PLAINS.equals(assignment.get(component.iterator().next())))
            .toList();
        int pinchComponentSize = plainsComponents.stream()
            .filter(component -> component.contains(new MacroCellPos(5, 5)))
            .mapToInt(Set::size)
            .findFirst().orElse(0);

        assertTrue(pinchComponentSize >= 9, "pinch-adjacent plains component should have widened to the target local bulk, was " + pinchComponentSize);
    }

    @Test
    void noOpWhenTheSeamIsAlreadyWideEnough() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x <= 1; x++) {
                assignment.put(new MacroCellPos(x, z), OCEAN);
            }
            for (int x = 2; x <= 7; x++) {
                assignment.put(new MacroCellPos(x, z), PLAINS);
            }
            for (int x = 8; x < 10; x++) {
                assignment.put(new MacroCellPos(x, z), BADLANDS);
            }
        }
        Map<MacroCellPos, BiomeId> before = Map.copyOf(assignment);
        Set<MacroCellPos> regionCellSet = Set.copyOf(GRID.cellsInRegion(REGION));

        SeamWidener.widenNarrowSeams(assignment, regionCellSet, graph());

        assertEquals(before, assignment, "a 6-cell-wide buffer is nowhere near the pinch radius - must be a no-op");
    }

    @Test
    void neverAssignsTwoMutuallyDisallowedBiomesNextToEachOther() {
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 10; z++) {
            int oceanEnd = (z == 5) ? 4 : 3;
            for (int x = 0; x <= oceanEnd; x++) {
                assignment.put(new MacroCellPos(x, z), OCEAN);
            }
            for (int x = oceanEnd + 1; x <= 5; x++) {
                assignment.put(new MacroCellPos(x, z), PLAINS);
            }
            for (int x = 6; x < 10; x++) {
                assignment.put(new MacroCellPos(x, z), BADLANDS);
            }
        }
        Set<MacroCellPos> regionCellSet = Set.copyOf(GRID.cellsInRegion(REGION));
        BiomeGraph graph = graph();

        SeamWidener.widenNarrowSeams(assignment, regionCellSet, graph);

        for (MacroCellPos cell : regionCellSet) {
            BiomeId biome = assignment.get(cell);
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !neighborBiome.equals(biome)) {
                    assertNotEquals(
                        List.of(OCEAN, BADLANDS), normalizedPair(biome, neighborBiome),
                        biome + " at " + cell + " ended up next to disallowed " + neighborBiome + " at " + neighbor
                    );
                }
            }
        }
    }

    private static List<BiomeId> normalizedPair(BiomeId x, BiomeId y) {
        return x.compareTo(y) <= 0 ? List.of(x, y) : List.of(y, x);
    }
}
