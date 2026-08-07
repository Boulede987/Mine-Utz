package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSolverTest {

    private static final BiomeId DESERT = id("desert");
    private static final BiomeId SAVANNA = id("savanna");
    private static final BiomeId BADLANDS = id("badlands");
    private static final BiomeId OCEAN = id("ocean");
    private static final BiomeId FOREST = id("forest");
    private static final BiomeId PLAINS = id("plains");

    /**
     * Desert: only savanna/badlands/plains. Ocean: only forest/plains.
     * Everything else falls to default-deny. Plains happens to be legal next to
     * every other node here, but that's just this graph's own data - nothing
     * requires or enforces it (see BiomeGraph's own doc).
     */
    private static BiomeGraph richGraph() {
        return new BiomeGraph(
            List.of(
                node("desert", 200, 300),
                node("savanna", 200, 300),
                node("badlands", 150, 250),
                node("ocean", 200, 300),
                node("forest", 150, 250),
                node("plains", 100, 200)
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

    private static RegionSolveInput inputFor(BiomeGraph graph, long worldSeed) {
        return new RegionSolveInput(graph, MacroGridConfig.DEFAULT, new RegionPos(0, 0), Map.of(), worldSeed);
    }

    @Test
    void solveIsDeterministicForSameInput() {
        RegionSolveInput input = inputFor(richGraph(), 12345L);

        RegionSolveResult first = RegionSolver.solve(input);
        RegionSolveResult second = RegionSolver.solve(input);

        assertEquals(first.assignment(), second.assignment());
        assertEquals(first.warnings(), second.warnings());
    }

    @Test
    void noAdjacencyRuleIsEverViolatedInTheResult() {
        BiomeGraph graph = richGraph();
        RegionSolveResult result = RegionSolver.solve(inputFor(graph, 999L));

        for (Map.Entry<MacroCellPos, BiomeId> entry : result.assignment().entrySet()) {
            for (MacroCellPos neighbor : entry.getKey().orthogonalNeighbors()) {
                BiomeId neighborBiome = result.assignment().get(neighbor);
                if (neighborBiome != null) {
                    assertTrue(
                        graph.isAllowed(entry.getValue(), neighborBiome),
                        entry.getValue() + " at " + entry.getKey() + " is not allowed next to " + neighborBiome + " at " + neighbor
                    );
                }
            }
        }
    }

    @Test
    void everyRegionCellGetsAssigned() {
        RegionSolveResult result = RegionSolver.solve(inputFor(richGraph(), 7L));

        assertEquals(MacroGridConfig.DEFAULT.regionSizeCellsPerSide() * MacroGridConfig.DEFAULT.regionSizeCellsPerSide(), result.assignment().size());
    }

    /**
     * BudgetPlanner checks capacity BEFORE ever attempting placement - a region too
     * small for any node's own minimum gets an empty plan, not a doomed attempt. The
     * single cell still needs SOME resolution though: GapFiller.resolveRemaining
     * picks deterministically among every legal candidate (all three, here - zero
     * neighbors means every node is vacuously legal) via its natural-order tie-break,
     * which happens to land on "filler" (lexicographically last of "a"/"b"/"filler").
     * Not special-cased - a differently-named third node would win instead.
     */
    @Test
    void aRegionTooSmallForAnyBiomeGetsAnEmptyPlanAndFallsToGapFillerInstead() {
        MacroGridConfig oneCellGrid = new MacroGridConfig(128, 1);
        BiomeGraph graph = new BiomeGraph(
            List.of(node("a", 700, 900), node("b", 700, 900), node("filler", 700, 900)),
            List.of(AdjacencyRule.allow(id("filler"), id("a"), 10), AdjacencyRule.allow(id("filler"), id("b"), 10))
        );
        RegionSolveInput input = new RegionSolveInput(graph, oneCellGrid, new RegionPos(0, 0), Map.of(), 1L);

        RegionSolveResult result = RegionSolver.solve(input);

        assertTrue(result.warnings().isEmpty(), "nothing was ever attempted, so nothing was dropped either");
        assertEquals(Map.of(new MacroCellPos(0, 0), id("filler")), result.assignment());
    }

    /**
     * Regression-relevant for the old design's actual bug (chronic over-subscription:
     * a fixed seed grid placed before knowing what sizes would be needed, causing most
     * seeds to fail). BudgetPlanner bounds total demand to the region's capacity before
     * placement starts, so a real biome graph at realistic sizes should place almost
     * everything - not a hard guarantee (adjacency conflicts can still drop a demand),
     * but dropped demands should be rare, not the majority.
     *
     * Deliberately NOT richGraph() here: its 150-300 block widths are small enough,
     * relative to MacroGridConfig.DEFAULT's real 64-block cells, that dozens of tiny
     * demands get generated and legitimately stress badlands/forest's narrow
     * adjacency (each only has 2 legal partners) - real signal about that graph at
     * that scale, not a bug. Using ModBiomeGraph-proportioned sizes (700-1000 etc.)
     * for a realistic "how well does this work in practice" check instead.
     */
    @Test
    void mostBudgetedDemandsSuccessfullyPlaceOnARealisticGraph() {
        BiomeGraph graph = new BiomeGraph(
            List.of(
                node("desert", 700, 1000),
                node("savanna", 500, 800),
                node("badlands", 500, 800),
                node("ocean", 600, 1000),
                node("forest", 500, 800),
                node("plains", 300, 600)
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

        RegionSolveResult result = RegionSolver.solve(inputFor(graph, 42L));

        assertTrue(result.droppedDemandCount() <= 2, "at most a couple of dropped demands expected, not chronic failure: " + result.warnings());
    }

    @Test
    void fixedBoundaryCellsAreNeverOverwritten() {
        BiomeId boundaryBiome = OCEAN;
        MacroCellPos boundaryCell = new MacroCellPos(-1, 0);
        RegionSolveInput input = new RegionSolveInput(
            richGraph(), MacroGridConfig.DEFAULT, new RegionPos(0, 0), Map.of(boundaryCell, boundaryBiome), 3L
        );

        RegionSolveResult result = RegionSolver.solve(input);

        assertFalse(result.assignment().containsKey(boundaryCell), "fixed boundary cell belongs to the neighbor region, not this one");
    }

    @Test
    void noAssignedCellEverViolatesAdjacencyAgainstAFixedBoundaryCell() {
        BiomeGraph graph = richGraph();
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        RegionPos region = new RegionPos(0, 0);

        // Whole west edge fixed to ocean, forcing gap-fill (not just growth) to
        // reckon with the boundary somewhere along that column.
        Map<MacroCellPos, BiomeId> fixedBoundary = new HashMap<>();
        for (int z = 0; z < grid.regionSizeCellsPerSide(); z++) {
            fixedBoundary.put(new MacroCellPos(-1, z), OCEAN);
        }

        RegionSolveInput input = new RegionSolveInput(graph, grid, region, Map.copyOf(fixedBoundary), 3L);
        RegionSolveResult result = RegionSolver.solve(input);

        for (int z = 0; z < grid.regionSizeCellsPerSide(); z++) {
            BiomeId edgeBiome = result.assignment().get(new MacroCellPos(0, z));
            assertTrue(graph.isAllowed(edgeBiome, OCEAN), "cell (0," + z + ") = " + edgeBiome + " is not allowed next to the fixed ocean boundary");
        }
    }
}
