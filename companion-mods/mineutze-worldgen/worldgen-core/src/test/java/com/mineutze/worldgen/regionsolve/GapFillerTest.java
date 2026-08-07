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
            List.of(node("a", 100, 900), node("b", 100, 900), node("filler", 100, 900)),
            List.of(AdjacencyRule.allow(A, B, 10), AdjacencyRule.allow(FILLER, A, 10), AdjacencyRule.allow(FILLER, B, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 16; z++) {
            assignment.put(new MacroCellPos(0, z), A);
            assignment.put(new MacroCellPos(15, z), B);
        }

        GapFiller.fillGaps(assignment, regionCells(), GRID, graph, WORLD_SEED);

        assertEquals(A, assignment.get(new MacroCellPos(3, 8)), "closer to the A column than the B column");
        assertEquals(B, assignment.get(new MacroCellPos(12, 8)), "closer to the B column than the A column");
    }

    @Test
    void sameWorldSeedProducesTheSameFill() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900), node("b", 100, 900), node("filler", 100, 900)),
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

        GapFiller.fillGaps(first, regionCells(), GRID, graph, WORLD_SEED);
        GapFiller.fillGaps(second, regionCells(), GRID, graph, WORLD_SEED);

        assertEquals(first, second);
    }

    @Test
    void neverAssignsTwoMutuallyDisallowedBiomesNextToEachOther() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900), node("b", 100, 900), node("filler", 100, 900)),
            // No A/B rule at all: default-deny, mutually incompatible.
            List.of(AdjacencyRule.allow(FILLER, A, 10), AdjacencyRule.allow(FILLER, B, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int z = 0; z < 16; z++) {
            assignment.put(new MacroCellPos(0, z), A);
            assignment.put(new MacroCellPos(4, z), B);
        }
        Set<MacroCellPos> regionCellSet = regionCells();

        GapFiller.fillGaps(assignment, regionCellSet, GRID, graph, WORLD_SEED);

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

    /**
     * No biome is universally legal anymore (see BiomeGraph's own doc), so a fully
     * empty region has no "obviously correct" choice - every node is equally legal
     * at a cell with zero assigned neighbors. GapFiller.resolveRemaining still has
     * to pick something deterministic rather than leave cells unassigned, and once
     * the first cell picks one, its neighbors inherit that choice via the majority
     * count - the whole region should end up a single, consistent biome, whichever
     * one the tie-break lands on first.
     */
    @Test
    void aCellWithNoLegalOrReachableNeighborStillGetsAConsistentDeterministicChoice() {
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 100, 900), node("filler", 100, 900)),
            List.of(AdjacencyRule.allow(FILLER, A, 10))
        );
        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        Set<MacroCellPos> regionCellSet = regionCells();

        GapFiller.fillGaps(assignment, regionCellSet, GRID, graph, WORLD_SEED);

        assertEquals(regionCellSet.size(), assignment.size(), "every cell must end up assigned");
        assertEquals(1, Set.copyOf(assignment.values()).size(), "a fully empty region has no signal to prefer one biome over another cell-to-cell - must resolve to a single consistent biome, not a patchwork");
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
            List.of(node("a", 100, 900, compact), node("c", 100, 900), node("filler", 100, 900)),
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

        GapFiller.fillGaps(assignment, regionCells(), GRID, graph, WORLD_SEED);

        assertNotEquals(A, assignment.get(new MacroCellPos(6, 8)), "a 1-wide gap must not let A flood the entire unclaimed far side");
    }

    /**
     * Reproduces the real failure (badlands/forest, no common legal neighbor after
     * plains lost its universal-filler status) in miniature, and exercises the
     * multi-blocking-neighbor case specifically: BADLANDS' only ally is OCEAN,
     * FOREST's only ally is BRIDGE, and OCEAN/BRIDGE are mutually allowed - so the
     * stuck cell touching BADLANDS and FOREST directly has zero legal candidates
     * (their ally sets don't intersect). {@code bestLegalChoice} tries candidate
     * biomes sorted, and "badlands" sorts before "bridge" - it reassigns the FOREST
     * corner (3,3) touching the stuck cell to OCEAN, which unblocks BADLANDS there.
     * But OCEAN itself is then blocked by TWO of ITS OWN forest neighbors, (4,3)
     * and (3,4) - not just the first one found: both must get resolved (to BRIDGE,
     * legal against forest, ocean, and forest again) before (3,3) as OCEAN is
     * actually legal, and only then can the stuck cell resolve. Each reassigned
     * cell is a non-cut-vertex of a 9-cell block with declared minimum 5 - safe to
     * lose from both connectivity and size.
     */
    @Test
    void aDeadEndCellGetsResolvedByReassigningANeighborInstead() {
        MacroGridConfig grid = new MacroGridConfig(128, 10);
        BiomeId badlands = id("badlands");
        BiomeId forest = id("forest");
        BiomeId ocean = id("ocean");
        BiomeId bridge = id("bridge");
        BiomeGraph graph = new BiomeGraph(
            List.of(node("badlands", 300, 900), node("forest", 300, 900), node("ocean", 100, 900), node("bridge", 100, 900)),
            List.of(
                AdjacencyRule.allow(badlands, ocean, 10),
                AdjacencyRule.allow(forest, bridge, 10),
                AdjacencyRule.allow(ocean, bridge, 10)
                // No badlands/forest, badlands/bridge, or ocean/forest rule - no direct bridge exists.
            )
        );

        Map<MacroCellPos, BiomeId> assignment = new HashMap<>();
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                assignment.put(new MacroCellPos(x, z), badlands);
            }
        }
        MacroCellPos stuckCell = new MacroCellPos(3, 2);
        for (int x = 3; x <= 5; x++) {
            for (int z = 3; z <= 5; z++) {
                assignment.put(new MacroCellPos(x, z), forest);
            }
        }
        Set<MacroCellPos> regionCellSet = Set.copyOf(grid.cellsInRegion(new RegionPos(0, 0)));

        GapFiller.fillGaps(assignment, regionCellSet, grid, graph, WORLD_SEED);

        assertEquals(badlands, assignment.get(stuckCell), "the dead-end cell should resolve to whatever became legal once the reassignment chain finished");
        assertEquals(ocean, assignment.get(new MacroCellPos(3, 3)), "the forest corner touching the dead-end cell should have been reassigned to ocean");
        assertEquals(bridge, assignment.get(new MacroCellPos(4, 3)), "ocean's own forest neighbor must also get resolved, not left illegally touching ocean");
        assertEquals(bridge, assignment.get(new MacroCellPos(3, 4)), "ocean's OTHER forest neighbor must also get resolved - fixing only the first blocking neighbor found would leave this one still illegally touching ocean");

        // The rest of the original 3x3 forest block, untouched by the reassignment chain,
        // must still be forest - not shrunk further, not disconnected. Not checking the
        // grid-wide forest total: GapFiller's normal BFS also legitimately expands forest
        // into the surrounding empty grid in this fixture (nothing else contests that
        // space), so the total can climb back up via cells completely unrelated to the
        // reassignment - that's correct behavior, just not what this assertion is about.
        Set<MacroCellPos> reassigned = Set.of(new MacroCellPos(3, 3), new MacroCellPos(4, 3), new MacroCellPos(3, 4));
        for (int x = 3; x <= 5; x++) {
            for (int z = 3; z <= 5; z++) {
                MacroCellPos originalBlockCell = new MacroCellPos(x, z);
                if (reassigned.contains(originalBlockCell)) {
                    continue;
                }
                assertEquals(forest, assignment.get(originalBlockCell), originalBlockCell + " (part of the original block) must still be forest");
            }
        }
    }
}
