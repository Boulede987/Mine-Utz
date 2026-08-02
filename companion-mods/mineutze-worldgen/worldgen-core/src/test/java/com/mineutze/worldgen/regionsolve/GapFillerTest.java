package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GapFillerTest {

    private static final MacroGridConfig GRID = new MacroGridConfig(128, 16);
    private static final RegionPos REGION = new RegionPos(0, 0);
    private static final BiomeId A = id("a");
    private static final BiomeId B = id("b");
    private static final BiomeId C = id("c");
    private static final BiomeId FILLER = id("filler");
    private static final long WORLD_SEED = 7L;

    private static Set<MacroCellPos> regionCells() {
        return Set.copyOf(GRID.cellsInRegion(REGION));
    }

    @Test
    void gapCellsJoinWhicheverPatchIsActuallyClosest() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900, false), node("b", 100, 900, false), node("filler", 100, 900, true)),
            List.of(AdjacencyRule.allow(A, B, 10), AdjacencyRule.allow(FILLER, A, 10), AdjacencyRule.allow(FILLER, B, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 16; z++) {
            assignment.put(new MacroCellPos(0, z), A);
            assignment.put(new MacroCellPos(15, z), B);
        }

        GapFiller.fillGaps(assignment, regionCells(), graph, WORLD_SEED);

        assertEquals(A, assignment.get(new MacroCellPos(3, 8)), "closer to the A column than the B column");
        assertEquals(B, assignment.get(new MacroCellPos(12, 8)), "closer to the B column than the A column");
    }

    @Test
    void sameWorldSeedProducesTheSameFill() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900, false), node("b", 100, 900, false), node("filler", 100, 900, true)),
            List.of(AdjacencyRule.allow(A, B, 10), AdjacencyRule.allow(FILLER, A, 10), AdjacencyRule.allow(FILLER, B, 10))
        );
        Map<MacroCellPos, BiomeId> first = new HashMap<>();
        Map<MacroCellPos, BiomeId> second = new HashMap<>();
        for (int z = 0; z < 16; z++) {
            first.put(new MacroCellPos(0, z), A);
            first.put(new MacroCellPos(15, z), B);
            second.put(new MacroCellPos(0, z), A);
            second.put(new MacroCellPos(15, z), B);
        }

        GapFiller.fillGaps(first, regionCells(), graph, WORLD_SEED);
        GapFiller.fillGaps(second, regionCells(), graph, WORLD_SEED);

        assertEquals(first, second);
    }

    @Test
    void neverAssignsTwoMutuallyDisallowedBiomesNextToEachOther() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900, false), node("b", 100, 900, false), node("filler", 100, 900, true)),
            // No A/B rule at all: default-deny, mutually incompatible.
            List.of(AdjacencyRule.allow(FILLER, A, 10), AdjacencyRule.allow(FILLER, B, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 16; z++) {
            assignment.put(new MacroCellPos(0, z), A);
            assignment.put(new MacroCellPos(4, z), B);
        }
        Set<MacroCellPos> regionCellSet = regionCells();

        GapFiller.fillGaps(assignment, regionCellSet, graph, WORLD_SEED);

        for (MacroCellPos cell : regionCellSet) {
            BiomeId biome = assignment.get(cell);
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !neighborBiome.equals(biome)) {
                    assertNotEquals(
                        List.of(A, B), normalizedPair(biome, neighborBiome),
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
    void aCellWithNoLegalOrReachableNeighborFallsBackToFiller() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900, false), node("filler", 100, 900, true)),
            List.of(AdjacencyRule.allow(FILLER, A, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        Set<MacroCellPos> regionCellSet = regionCells();

        GapFiller.fillGaps(assignment, regionCellSet, graph, WORLD_SEED);

        assertEquals(regionCellSet.size(), assignment.size(), "every cell must end up assigned");
        assignment.values().forEach(biome -> assertEquals(FILLER, biome, "no assigned neighbors anywhere - only filler is legal by construction"));
    }

    /**
     * A wall of C (legal next to A) splits the region at x=5 with exactly one gap
     * at (5, 8). A is a solid, already-committed block on the left (x=0..3); the
     * right side (x=6..15) starts completely unclaimed. A COMPACT profile
     * (minSameBiomeNeighbors=2) should let A touch the gap cell itself (it has
     * strong Moore-neighborhood support from the solid block behind it) but must
     * not let A flood through into the open right side - the first cell past the
     * gap only ever has 1 same-biome Moore-neighbor (the gap cell alone).
     */
    @Test
    void cohesionProfileStopsAPatchFloodingThroughTheGapDuringFill() {
        GrowthCohesionProfile compact = new GrowthCohesionProfile(2, 0, 0);
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900, false, compact), node("c", 100, 900, false), node("filler", 100, 900, true)),
            List.of(AdjacencyRule.allow(A, C, 10), AdjacencyRule.allow(FILLER, A, 10), AdjacencyRule.allow(FILLER, C, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z < 16; z++) {
                assignment.put(new MacroCellPos(x, z), A);
            }
        }
        for (int z = 0; z < 16; z++) {
            if (z != 8) {
                assignment.put(new MacroCellPos(5, z), C);
            }
        }

        GapFiller.fillGaps(assignment, regionCells(), graph, WORLD_SEED);

        assertNotEquals(A, assignment.get(new MacroCellPos(6, 8)), "a 1-wide gap must not let A flood the entire unclaimed far side");
    }
}
