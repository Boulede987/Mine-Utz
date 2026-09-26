package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.everyCellHolds;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableRequiring;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static com.mineutze.worldgen.plan.ToroidalTestTables.legalPairWithSizes;
import static com.mineutze.worldgen.plan.ToroidalTestTables.noLegalTriangleCatalog;
import static com.mineutze.worldgen.plan.ToroidalTestTables.tableWithNoLegalTriangle;
import static com.mineutze.worldgen.plan.ToroidalTestTables.threeColoring;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withGroup;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirement;
import static com.mineutze.worldgen.plan.ToroidalTestTables.withRequirementText;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToroidalSizedLocalSearchTest {

    private static final double ATOM_WIDTH = 300;
    private static final ShapeRule THICKNESS_ONLY = new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, 3, 7, 2);
    private static final long SEED = 4321L;
    private static final long GENEROUS_STEPS = 5_000_000;

    private static ClusterSizeModel model(RawRuleTable table) {
        return new ClusterSizeModel(table, ATOM_WIDTH, THICKNESS_ONLY);
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

    /** With only a perimeter limit (no thickness checks), a straight line of eight cells is in range but its perimeter of 34 exceeds 1.5 x the minimum 20 = 30 by exactly 4. */
    @Test
    void verifyRejectsAClusterWhosePerimeterExceedsTheRuleByExactlyTheExcessEdges() {
        ToroidalGrid grid = new ToroidalGrid(10, 10);
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B);
        ClusterSizeModel perimeterOnly = new ClusterSizeModel(table, ATOM_WIDTH, new ShapeRule(1.5, Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
        Map<ToroidalPos, BiomeId> line = blobAndRest(grid, A, B, cells(0, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0));

        String message = assertThrows(IllegalStateException.class, () -> ToroidalSizedLocalSearch.verify(table, grid, perimeterOnly, line)).getMessage();

        assertTrue(message.contains("cluster violation=4"), message);
    }

    /** The same eight cells packed as a flower plus one cell have perimeter 20, well inside the limit. */
    @Test
    void verifyAcceptsACompactClusterUnderThePerimeterLimit() {
        ToroidalGrid grid = new ToroidalGrid(10, 10);
        RawRuleTable table = fullyLegalTableWithWidths(300, 3000, A, B);
        ClusterSizeModel perimeterOnly = new ClusterSizeModel(table, ATOM_WIDTH, new ShapeRule(1.5, Integer.MAX_VALUE, Integer.MAX_VALUE, 2));

        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, perimeterOnly, blobAndRest(grid, A, B, cells(withCells(FLOWER, 5, 3)))));
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

    /** Same cross-check as above but with a perimeter limit switched on, so the search's own perimeter counting is what must agree with the independent recomputation. */
    @Test
    void reportedBestCostAgreesWithTheIndependentCostWhenAPerimeterLimitIsActive() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A, B, C);
        ClusterSizeModel withPerimeterLimit = new ClusterSizeModel(table, ATOM_WIDTH, new ShapeRule(1.2, 3, 7, 2));

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, withPerimeterLimit, SEED, 30_000);

        assertNull(result.assignment());
        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, withPerimeterLimit, result.closestAssignment()));
    }

    /** A is pinned to exactly 8 cells and the strictest perimeter ratio (1.0) allows it only the 20-edge minimum, so the search must find a genuinely compact 8-cell shape; a search that ignored perimeter would "solve" with a worse one, and its own verify would throw. */
    @Test
    void searchFindsACompactShapeWhenAStrictPerimeterLimitBinds() {
        ToroidalGrid grid = new ToroidalGrid(10, 10);
        RawRuleTable table = legalPairWithSizes(A, 848, 849, B, 300, 3000);
        ClusterSizeModel strict = new ClusterSizeModel(table, ATOM_WIDTH, new ShapeRule(1.0, Integer.MAX_VALUE, Integer.MAX_VALUE, 2));

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, strict, SEED, GENEROUS_STEPS);

        assertNotNull(result.assignment(), "a compact 8-cell blob inside a 100-cell torus exists");
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, strict, result.assignment()));
    }

    /**
     * The mirror of the test above: A is pinned to exactly 8 cells and a 1.5x PERIMETER FLOOR (no ceiling) demands at
     * least 30 edges - the compact flower+1 shape (perimeter 20) no longer qualifies, only a genuinely elongated one
     * does (a straight line of 8 has perimeter 34). Proves the floor is a real end-to-end guarantee the search must
     * satisfy, not just a unit-level formula.
     */
    @Test
    void searchFindsAnElongatedShapeWhenAPerimeterFloorBinds() {
        ToroidalGrid grid = new ToroidalGrid(10, 10);
        RawRuleTable table = legalPairWithSizes(A, 848, 849, B, 300, 3000);
        ClusterSizeModel floored = new ClusterSizeModel(table, ATOM_WIDTH, new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, 1.5, Integer.MAX_VALUE, Integer.MAX_VALUE, 2));

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, floored, SEED, GENEROUS_STEPS);

        assertNotNull(result.assignment(), "an elongated 8-cell shape inside a 100-cell torus exists");
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, floored, result.assignment()));
    }

    /** Only A-B is legal, so a 3-coloring on 6x6 has 36 edges of each kind: A|B fine, A|C and B|C illegal. */
    @Test
    void illegalPairCountsNameEachIllegalBiomePairWithItsEdgeCount() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = tableWithNoLegalTriangle(300, 300);

        Map<String, Integer> counts = ToroidalSizedLocalSearch.illegalPairCounts(table, grid, threeColoring(grid, A, B, C));

        assertEquals(Map.of(A + " | " + C, 36, B + " | " + C, 36), counts);
    }

    @Test
    void illegalPairCountsAreEmptyForAPatternWithoutIllegalEdges() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = singleCellClusterTable();

        assertTrue(ToroidalSizedLocalSearch.illegalPairCounts(table, grid, threeColoring(grid, A, B, C)).isEmpty());
    }

    private static final ToroidalSizedLocalSearch.Schedule STEADY = ToroidalSizedLocalSearch.Schedule.constant(0.4);
    /** So hot that nearly every plausible relabel is accepted, which is what makes drift in its bookkeeping visible. */
    private static final ToroidalSizedLocalSearch.Schedule BOILING = ToroidalSizedLocalSearch.Schedule.constant(3.0);
    /** Warm enough that clusters keep changing yet stay within a wide size range, so many whole-cluster relabels are eligible and accepted. */
    private static final ToroidalSizedLocalSearch.Schedule WARM = ToroidalSizedLocalSearch.Schedule.constant(1.0);

    @Test
    void trackedCostStaysExactWhenRelabelsRunUnderAStrictPerimeterLimit() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableWithWidths(700, 900, A, B, C);
        ClusterSizeModel strict = new ClusterSizeModel(table, ATOM_WIDTH, new ShapeRule(1.1, Integer.MAX_VALUE, Integer.MAX_VALUE, 2));

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, strict, SEED, 40_000, BOILING, () -> false, 0.5);

        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, strict, result.closestAssignment()));
    }

    /** Sizes 1..9 cells, so a relabel that merges a cluster into a same-biome neighbor can push the merged cluster over its maximum: the move must account for that neighbor's changed cost. Over 100 relabels are accepted in this walk. */
    @Test
    void trackedCostStaysExactWhenRelabelsMergeClustersOfTheSameBiome() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableWithWidths(300, 900, A, B, C);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 80_000, WARM, () -> false, 0.8);

        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), result.closestAssignment()));
    }

    /** Only A-B is legal, so C clusters touch illegal edges; relabeling one to A or B removes them, and the move must count exactly those edges. Over 100 relabels are accepted in this walk. */
    @Test
    void trackedCostStaysExactWhenRelabelsRemoveIllegalEdges() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = tableWithNoLegalTriangle(300, 1200);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 80_000, WARM, () -> false, 0.8);

        assertNull(result.assignment());
        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), result.closestAssignment()));
    }

    /** A solved state is verified independently inside the search, so any cost drift caused by relabel moves would make this throw instead of return. */
    @Test
    void searchMixingInRelabelMovesStillSolvesAndVerifiesASmallInstance() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(500, 600, A, B, C);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, GENEROUS_STEPS, STEADY, () -> false, 0.3);

        assertNotNull(result.assignment());
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), result.assignment()));
    }

    @Test
    void relabelProbabilityOutsideZeroToOneIsRejected() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTableForArguments();

        assertThrows(IllegalArgumentException.class, () -> ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 1_000, STEADY, () -> false, -0.1));
        assertThrows(IllegalArgumentException.class, () -> ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 1_000, STEADY, () -> false, 1.1));
    }

    private static RawRuleTable solvableTableForArguments() {
        return fullyLegalTableWithWidths(500, 600, A, B, C);
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

    /** In the 3-coloring every cell has three neighbors of each other color, so a one-cell A shares exactly 3 edges with B. */
    @Test
    void aClusterBelowItsRequiredBorderCostsExactlyTheMissingEdgesForEachClusterOfIt() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableRequiring(300, 300, A, B, 4, A, B, C);

        ToroidalSizedLocalSearch.Breakdown breakdown = ToroidalSizedLocalSearch.breakdown(table, grid, model(table), threeColoring(grid, A, B, C));

        assertEquals(12, breakdown.clusterViolation(), "12 one-cell A clusters, each 1 edge short of 4");
        assertEquals(0, breakdown.illegalEdges());
        assertEquals(0, breakdown.missingBiomes());
    }

    @Test
    void aClusterMeetingItsRequiredBorderCostsNothing() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableRequiring(300, 300, A, B, 3, A, B, C);

        assertEquals(0, ToroidalSizedLocalSearch.cost(table, grid, model(table), threeColoring(grid, A, B, C)));
    }

    @Test
    void theShortfallIsMeasuredAgainstTheRequiredBiomeNotAnyNeighbor() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = RawRuleTable.from(withRequirement(ToroidalTestTables.fullyLegalCatalog(300, 300, A, B, C), A, C, 5));

        assertEquals(24, ToroidalSizedLocalSearch.breakdown(table, grid, model(table), threeColoring(grid, A, B, C)).clusterViolation(), "12 A cells, each 2 edges short of 5 with C");
    }

    /** Without the requirement almost any pattern is valid, so a solved state that verifies proves the search steered by it: verify() would throw otherwise. */
    @Test
    void searchSolvesAnInstanceWhereEveryAClusterMustShareABorderWithB() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableRequiring(500, 600, A, B, 4, A, B, C);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, GENEROUS_STEPS);

        assertNotNull(result.assignment(), "3-4 cell A clusters can share 4 of their 9-12 outline edges with B");
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), result.assignment()));
    }

    @Test
    void verifyRejectsAPatternWhereARequiredBorderIsShort() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableRequiring(300, 300, A, B, 4, A, B, C);

        String message = rejectionMessage(table, grid, threeColoring(grid, A, B, C));

        assertFalse(message.contains("cluster violation=0"), message);
    }

    private static RawRuleTable unsolvableTableWithARequirement() {
        return RawRuleTable.from(withRequirement(noLegalTriangleCatalog(300, 1200), A, B, 2));
    }

    /** No legal tiling exists, so the walk never ends early; single-cell moves must price the clusters of a THIRD biome whose required border a cell change takes away or adds. */
    @Test
    void trackedCostStaysExactUnderRequirementsForSingleCellMoves() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = unsolvableTableWithARequirement();

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 80_000, WARM, () -> false, 0.0);

        assertNull(result.assignment());
        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), result.closestAssignment()));
    }

    @Test
    void trackedCostStaysExactUnderRequirementsWhenRelabelsRunToo() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = unsolvableTableWithARequirement();

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 80_000, BOILING, () -> false, 0.5);

        assertNull(result.assignment());
        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), result.closestAssignment()));
    }

    /** Every biome needs 25 cells on a 64-cell torus, so the walk churns through many clusters, most of them touching none of the required biome: a counter left over from an earlier cluster would show as drift. */
    @Test
    void trackedCostStaysExactWhenMostClustersTouchNoneOfTheRequiredBiome() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = fullyLegalTableRequiring(1500, 1500, A, B, 3, A, B, C);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 80_000, BOILING, () -> false, 0.5);

        assertNull(result.assignment());
        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), result.closestAssignment()));
    }

    private static RawRuleTable tableWith(String requirementOfA, int width) {
        return tableWith(requirementOfA, width, width);
    }

    private static RawRuleTable tableWith(String requirementOfA, int minWidth, int maxWidth) {
        return RawRuleTable.from(withRequirementText(withGroup(withGroup(ToroidalTestTables.fullyLegalCatalog(minWidth, maxWidth, A, B, C), "bc", B, C), "justB", B), A, requirementOfA));
    }

    private static int clusterViolationOfTheThreeColoring(String requirementOfA) {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = tableWith(requirementOfA, 300);
        return ToroidalSizedLocalSearch.breakdown(table, grid, model(table), threeColoring(grid, A, B, C)).clusterViolation();
    }

    /** Every one-cell A in the 3-coloring has three B edges and three C edges, and there are twelve of them. */
    @Test
    void anOrCostsTheNearestAlternativeForEachCluster() {
        assertEquals(12, clusterViolationOfTheThreeColoring("test:b >= 4 or test:c >= 4"), "1 edge short either way");
        assertEquals(0, clusterViolationOfTheThreeColoring("test:b >= 3 or test:c >= 4"));
    }

    @Test
    void anAndCostsTheSumOfItsPartsForEachCluster() {
        assertEquals(24, clusterViolationOfTheThreeColoring("test:b >= 4 and test:c >= 4"));
        assertEquals(0, clusterViolationOfTheThreeColoring("test:b >= 3 and test:c >= 3"));
    }

    @Test
    void aGroupCostsWhatIsMissingFromTheCombinedEdges() {
        assertEquals(0, clusterViolationOfTheThreeColoring("bc >= 6"), "3 B edges and 3 C edges make 6 with the group");
        assertEquals(12, clusterViolationOfTheThreeColoring("justB >= 4"), "only the 3 B edges count for a group of B alone");
    }

    @Test
    void searchSolvesAnInstanceWhereTheBorderMayBeWithEitherOfTwoBiomes() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = tableWith("test:b >= 4 or test:c >= 4", 500, 600);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, GENEROUS_STEPS);

        assertNotNull(result.assignment());
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), result.assignment()));
    }

    private static final String MIXED_REQUIREMENT = "(test:b >= 2 and bc >= 5) or test:c >= 4";

    /** Widths of 1500 need 25 cells per biome on a 64-cell torus: unsolvable, so the walk never stops early. */
    @Test
    void trackedCostStaysExactForANestedRequirementWithSingleCellMoves() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = tableWith(MIXED_REQUIREMENT, 1500);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 80_000, WARM, () -> false, 0.0);

        assertNull(result.assignment());
        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), result.closestAssignment()));
    }

    @Test
    void trackedCostStaysExactForANestedRequirementWhenRelabelsRunToo() {
        ToroidalGrid grid = new ToroidalGrid(8, 8);
        RawRuleTable table = tableWith(MIXED_REQUIREMENT, 1500);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), SEED, 80_000, BOILING, () -> false, 0.5);

        assertNull(result.assignment());
        assertEquals(result.bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), result.closestAssignment()));
    }
}
