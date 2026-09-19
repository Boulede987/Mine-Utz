package com.mineutze.worldgen.coherence;

import org.junit.jupiter.api.Test;

import static com.mineutze.worldgen.coherence.ToroidalTestTables.A;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.B;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.C;
import static com.mineutze.worldgen.coherence.ToroidalTestTables.fullyLegalTableWithWidths;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelSeedSearchTest {

    private static final double ATOM_WIDTH = 300;
    private static final long BASE_SEED = 900L;
    private static final ToroidalSizedLocalSearch.Schedule SCHEDULE = ToroidalSizedLocalSearch.Schedule.constant(0.4);

    private static ClusterSizeModel model(RawRuleTable table) {
        return new ClusterSizeModel(table, ATOM_WIDTH, new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, 3, 13, 2));
    }

    /** Solvable, but the short budget below makes most seeds fail and a few succeed - the situation where parallel scheduling could change the answer. */
    private static RawRuleTable solvableTable() {
        return fullyLegalTableWithWidths(500, 600, A, B, C);
    }

    private static final long SHORT_BUDGET = 3_000;

    private static boolean solvesWithinTheShortBudget(RawRuleTable table, ToroidalGrid grid, int index) {
        return ToroidalSizedLocalSearch.search(table, grid, model(table), BASE_SEED + index, SHORT_BUDGET, SCHEDULE).assignment() != null;
    }

    /** The oracle: run the seeds one after another, exactly as a single thread would, and report the first index that solves (or -1). */
    private static int firstSolvingIndexRunSequentially(RawRuleTable table, ToroidalGrid grid, int seedCount) {
        for (int index = 0; index < seedCount; index++) {
            if (solvesWithinTheShortBudget(table, grid, index)) {
                return index;
            }
        }
        return -1;
    }

    @Test
    void solvesASolvableInstanceAndTheSolutionVerifies() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();

        ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 8, 5_000_000, SCHEDULE, 4);

        assertTrue(outcome.solved());
        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), outcome.result().assignment()));
    }

    @Test
    void winnerIsTheLowestSeedIndexThatSolvesWhenSeedsAreRunOneByOne() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();
        int seedCount = 12;
        int expectedWinner = firstSolvingIndexRunSequentially(table, grid, seedCount);
        long solvingSeeds = java.util.stream.IntStream.range(0, seedCount).filter(index -> solvesWithinTheShortBudget(table, grid, index)).count();
        assertTrue(expectedWinner > 0 && solvingSeeds >= 2, "instance must make the winner a later seed with rivals behind it, else this test cannot tell winners apart");

        ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, seedCount, SHORT_BUDGET, SCHEDULE, 4);

        assertEquals(BASE_SEED + expectedWinner, outcome.seed());
    }

    /** Every seed runs at once, so a fast higher seed finishes while a slower lower one is still walking: the lower one must not be cut short. Repeated because the interleaving differs run to run. */
    @Test
    void aFastHigherSeedNeverCutsShortASlowerLowerSeedThatWouldHaveWon() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();
        long expectedWinner = BASE_SEED + firstSolvingIndexRunSequentially(table, grid, 12);

        for (int repetition = 0; repetition < 40; repetition++) {
            ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 12, SHORT_BUDGET, SCHEDULE, 12);

            assertEquals(expectedWinner, outcome.seed(), "repetition " + repetition);
        }
    }

    @Test
    void theOutcomeDoesNotDependOnHowManyThreadsRan() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();

        ParallelSeedSearch.Outcome single = ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 12, SHORT_BUDGET, SCHEDULE, 1);
        ParallelSeedSearch.Outcome many = ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 12, SHORT_BUDGET, SCHEDULE, 6);

        assertEquals(single.seed(), many.seed());
        assertEquals(single.result().assignment(), many.result().assignment());
    }

    @Test
    void whenNoSeedSolvesTheOutcomeIsNotSolvedAndKeepsTheClosestState() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A, B, C);

        ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 4, 20_000, SCHEDULE, 2);

        assertFalse(outcome.solved());
        assertNull(outcome.result().assignment());
        assertEquals(outcome.result().bestCost(), ToroidalSizedLocalSearch.cost(table, grid, model(table), outcome.result().closestAssignment()));
    }

    @Test
    void whenNoSeedSolvesTheClosestStateIsTheLowestCostAmongAllSeeds() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A, B, C);
        int lowestCost = Integer.MAX_VALUE;
        for (int index = 0; index < 4; index++) {
            lowestCost = Math.min(lowestCost, ToroidalSizedLocalSearch.search(table, grid, model(table), BASE_SEED + index, 20_000, SCHEDULE).bestCost());
        }

        ParallelSeedSearch.Outcome outcome = ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 4, 20_000, SCHEDULE, 3);

        assertEquals(lowestCost, outcome.result().bestCost());
    }

    @Test
    void rejectsNonPositiveArguments() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();

        assertThrows(IllegalArgumentException.class, () -> ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 0, 1_000, SCHEDULE, 2));
        assertThrows(IllegalArgumentException.class, () -> ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 4, 0, SCHEDULE, 2));
        assertThrows(IllegalArgumentException.class, () -> ParallelSeedSearch.search(table, grid, model(table), BASE_SEED, 4, 1_000, SCHEDULE, 0));
    }

    @Test
    void aStoppedSearchReportsNotSolvedAndHasTakenFarFewerStepsThanItsBudget() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A, B, C);

        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(table, grid, model(table), BASE_SEED, 5_000_000, SCHEDULE, () -> true);

        assertNull(result.assignment());
        assertTrue(result.steps() < 10_000, "stopped at the first check, not the full budget: " + result.steps());
        assertNotNull(result.closestAssignment());
    }
}
