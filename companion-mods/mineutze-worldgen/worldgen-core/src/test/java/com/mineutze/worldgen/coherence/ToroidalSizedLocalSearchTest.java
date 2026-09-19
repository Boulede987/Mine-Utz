package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.mineutze.worldgen.coherence.ToroidalTestTables.A;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.B;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.C;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.everyCellHolds;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.fullyLegalTableWithWidths;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.tableWithNoLegalTriangle;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.threeColoring;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToroidalSizedLocalSearchTest {

    private static final double ATOM_WIDTH = 300;
    private static final int TAIL_REACH = 2;
    private static final int FLOWER_FROM = 7;
    private static final long SEED = 4321L;
    private static final long GENEROUS_STEPS = 5_000_000;

    private static ClusterSizeModel model(RawRuleTable table) {
        return new ClusterSizeModel(table, ATOM_WIDTH, TAIL_REACH, FLOWER_FROM);
    }

    /** Widths 300..300 mean exactly one cell per cluster, so the 3-coloring's single-cell clusters are all valid. */
    private static RawRuleTable singleCellClusterTable() {
        return fullyLegalTableWithWidths(300, 300, A, B, C);
    }

    private static String rejectionMessage(RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment) {
        return assertThrows(
            IllegalStateException.class, () -> ToroidalSizedLocalSearch.verify(table, grid, model(table), assignment)
        ).getMessage();
    }

    @Test
    void verifyAcceptsATilingWhereEveryClusterIsValidLegalAndRound() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = singleCellClusterTable();

        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), threeColoring(grid, A, B, C)));
    }

    @Test
    void verifyRejectsAnIllegalAdjacencyBetweenDifferentBiomes() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = tableWithNoLegalTriangle(300, 300);

        String message = rejectionMessage(table, grid, threeColoring(grid, A, B, C));

        assertFalse(message.contains("illegal edges=0"), message);
    }

    @Test
    void verifyRejectsAClusterSmallerThanItsBiomeMinimum() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(424, 500, A, B, C);

        String message = rejectionMessage(table, grid, threeColoring(grid, A, B, C));

        assertFalse(message.contains("cluster violation=0"), message);
    }

    @Test
    void verifyRejectsAClusterLargerThanItsBiomeMaximum() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(300, 300, A);

        String message = rejectionMessage(table, grid, everyCellHolds(grid, A));

        assertFalse(message.contains("cluster violation=0"), message);
    }

    /** Right area, wrong shape: a straight line of five cells fits A's range but is a snake - no cell of it sits in a triangle. */
    @Test
    void verifyRejectsAThinLineThatFitsTheSizeRange() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B);

        String message = rejectionMessage(table, grid, blobAndRest(grid, A, B, cells(0, 0, 1, 0, 2, 0, 3, 0, 4, 0)));

        assertFalse(message.contains("cluster violation=0"), message);
    }

    @Test
    void verifyAcceptsAThreeCellTriangleWhichIsTheSmallestSolidShape() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B);

        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), blobAndRest(grid, A, B, cells(3, 3, 4, 3, 3, 4))));
    }

    /** A band two cells wide that wraps the whole torus is wide enough for a triangle everywhere but has no flower core, so at this size it is a snake. */
    @Test
    void verifyRejectsAStripTwoCellsWideBecauseALargeClusterNeedsAFlower() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B);
        Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            assignment.put(pos, pos.r() < 2 ? A : B);
        }

        String message = rejectionMessage(table, grid, assignment);

        assertTrue(message.contains("cluster violation=16"), "every cell of the 16-cell band is thin: " + message);
    }

    private static final int[] FLOWER = {3, 3, 4, 3, 4, 2, 3, 2, 2, 3, 2, 4, 3, 4};

    private static int[] withCells(int[] base, int... extra) {
        int[] all = java.util.Arrays.copyOf(base, base.length + extra.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    /** A flower plus one cell beyond its rim: that cell is two steps from the core, within the tail reach of 2. */
    @Test
    void verifyAcceptsAFlowerWithABumpWithinTheTailReach() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B);

        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), blobAndRest(grid, A, B, cells(withCells(FLOWER, 5, 3)))));
    }

    /** One more cell along the same tail sits three steps from the core, past the reach of 2: exactly one thin cell. */
    @Test
    void verifyRejectsATailLongerThanTheReachByExactlyTheCellsBeyondIt() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B);

        String message = rejectionMessage(table, grid, blobAndRest(grid, A, B, cells(withCells(FLOWER, 5, 3, 6, 3))));

        assertTrue(message.contains("cluster violation=1"), message);
    }

    private static java.util.Set<ToroidalPos> cells(int... coordinates) {
        java.util.Set<ToroidalPos> result = new java.util.HashSet<>();
        for (int index = 0; index < coordinates.length; index += 2) {
            result.add(new ToroidalPos(coordinates[index], coordinates[index + 1]));
        }
        return result;
    }

    private static Map<ToroidalPos, BiomeId> blobAndRest(ToroidalGrid grid, BiomeId blob, BiomeId rest, java.util.Set<ToroidalPos> blobCells) {
        Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            assignment.put(pos, blobCells.contains(pos) ? blob : rest);
        }
        return assignment;
    }

    @Test
    void verifyRejectsAPatternMissingABiome() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B, C);
        Map<ToroidalPos, BiomeId> twoBands = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            twoBands.put(pos, pos.q() < 4 ? A : B);
        }

        String message = rejectionMessage(table, grid, twoBands);

        assertFalse(message.contains("missing biomes=0"), message);
        assertTrue(message.contains("cluster violation=0"), "the bands themselves are fine, only C is missing: " + message);
    }

    @Test
    void costOfAValidPatternIsZero() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = singleCellClusterTable();

        assertEquals(0, ToroidalSizedLocalSearch.cost(table, grid, model(table), threeColoring(grid, A, B, C)));
    }

    @Test
    void searchSolvesASmallInstanceWithMultiCellClusters() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(500, 600, A, B, C);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, GENEROUS_STEPS);

        assertNotNull(result.assignment(), "3-4 cell clusters of 3 mutually legal biomes on a 6x6 torus are solvable");
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), result.assignment()));
        assertEquals(0, ToroidalSizedLocalSearch.cost(table, grid, model(table), result.assignment()));
    }

    @Test
    void searchReportsFailureInsteadOfAFalseSolutionWhenNoValidPatternExists() {
        ToroidalGrid grid = new ToroidalGrid(3, 3);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 20_000);

        assertNull(result.assignment(), "a biome needing 25 cells cannot fit on a 9-cell torus");
        assertTrue(result.bestCost() > 0);
    }

    /** The search tracks its cost incrementally; the state it reports must cost exactly what an independent full recomputation says. */
    @Test
    void reportedBestCostEqualsTheIndependentCostOfTheReportedState() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A, B, C);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 30_000);

        assertNull(result.assignment());
        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), result.closestAssignment()));
    }

    @Test
    void sameSeedGivesTheSameOutcome() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A, B, C);

        ToroidalSizedLocalSearch.Result first = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 20_000);
        ToroidalSizedLocalSearch.Result second = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 20_000);

        assertEquals(first.bestCost(), second.bestCost());
        assertEquals(first.closestAssignment(), second.closestAssignment());
    }

    @Test
    void aConstantScheduleBehavesExactlyLikeAFixedTemperature() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A, B, C);

        ToroidalSizedLocalSearch.Result fixed = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 20_000, 0.3);
        ToroidalSizedLocalSearch.Result scheduled = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 20_000, ToroidalSizedLocalSearch.Schedule.constant(0.3));

        assertEquals(fixed.bestCost(), scheduled.bestCost());
        assertEquals(fixed.closestAssignment(), scheduled.closestAssignment());
    }

    @Test
    void aCoolingCycleStartsAtTheHighTemperatureAndEndsAtTheLowOne() {
        ToroidalSizedLocalSearch.Schedule cycle = new ToroidalSizedLocalSearch.Schedule(2.0, 0.1, 4_096);

        assertEquals(2.0, cycle.temperatureAtLevel(cycle.levelAtStep(0)), 1e-12);
        assertEquals(0.1, cycle.temperatureAtLevel(cycle.levelAtStep(4_095)), 1e-12);
    }

    @Test
    void aCoolingCycleRestartsHotWhenTheNextCycleBegins() {
        ToroidalSizedLocalSearch.Schedule cycle = new ToroidalSizedLocalSearch.Schedule(2.0, 0.1, 4_096);

        assertEquals(cycle.levelAtStep(0), cycle.levelAtStep(4_096));
        assertTrue(cycle.levelAtStep(2_048) > cycle.levelAtStep(0), "mid-cycle is cooler than the start");
    }

    /** Solvable instance, so each walk ends at its own solution after its own number of steps - the pair (steps, pattern) is the walk's fingerprint. */
    @Test
    void aReheatingScheduleWalksDifferentlyFromEitherOfItsExtremeTemperatures() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(500, 600, A, B, C);
        ToroidalSizedLocalSearch.Schedule reheating = new ToroidalSizedLocalSearch.Schedule(0.6, 0.15, 4_096);

        ToroidalSizedLocalSearch.Result cold = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, GENEROUS_STEPS, 0.15);
        ToroidalSizedLocalSearch.Result hot = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, GENEROUS_STEPS, 0.6);
        ToroidalSizedLocalSearch.Result cycled = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, GENEROUS_STEPS, reheating);

        assertFalse(cold.steps() == cycled.steps() && cold.closestAssignment().equals(cycled.closestAssignment()), "must differ from the always-cold walk");
        assertFalse(hot.steps() == cycled.steps() && hot.closestAssignment().equals(cycled.closestAssignment()), "must differ from the always-hot walk");
    }

    @Test
    void searchWithAReheatingScheduleStillSolvesAndVerifiesASmallInstance() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(500, 600, A, B, C);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(
            table, grid, model(table), SEED, GENEROUS_STEPS, new ToroidalSizedLocalSearch.Schedule(0.6, 0.2, 50_000)
        );

        assertNotNull(result.assignment());
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), result.assignment()));
    }

    @Test
    void scheduleRejectsALowTemperatureAboveTheHighOne() {
        assertThrows(IllegalArgumentException.class, () -> new ToroidalSizedLocalSearch.Schedule(0.2, 0.5, 1_000));
    }

    @Test
    void scheduleRejectsANonPositiveLowTemperature() {
        assertThrows(IllegalArgumentException.class, () -> new ToroidalSizedLocalSearch.Schedule(0.5, 0.0, 1_000));
    }

    @Test
    void scheduleRejectsAnEmptyCycle() {
        assertThrows(IllegalArgumentException.class, () -> new ToroidalSizedLocalSearch.Schedule(0.5, 0.2, 0));
    }
}
