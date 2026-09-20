package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static com.mineutze.worldgen.plan.ToroidalTestTables.A;
import static com.mineutze.worldgen.plan.ToroidalTestTables.B;
import static com.mineutze.worldgen.plan.ToroidalTestTables.C;
import static com.mineutze.worldgen.plan.ToroidalTestTables.fullyLegalTableWithWidths;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToroidalSizedPatternGeneratorTest {

    private static final ToroidalSizedLocalSearch.Schedule SCHEDULE = ToroidalSizedLocalSearch.Schedule.constant(0.4);
    private static final long STEPS_PER_SEED = 4_000;
    private static final int MAX_SEEDS = 40;

    private static ClusterSizeModel model(RawRuleTable table) {
        return new ClusterSizeModel(table, 300, new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, 3, 13, 2));
    }

    private static RawRuleTable solvableTable() {
        return fullyLegalTableWithWidths(500, 600, A, B, C);
    }

    @Test
    void producesAPatternThatPassesTheIndependentVerification() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();

        ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(table, grid, model(table), 12345L, STEPS_PER_SEED, MAX_SEEDS, SCHEDULE, 4);

        assertDoesNotThrow(() -> ToroidalSizedLocalSearch.verify(table, grid, model(table), generated.pattern()));
    }

    @Test
    void sameWorldSeedGivesTheSamePattern() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();

        ToroidalSizedPatternGenerator.Generated first = ToroidalSizedPatternGenerator.generate(table, grid, model(table), 777L, STEPS_PER_SEED, MAX_SEEDS, SCHEDULE, 4);
        ToroidalSizedPatternGenerator.Generated second = ToroidalSizedPatternGenerator.generate(table, grid, model(table), 777L, STEPS_PER_SEED, MAX_SEEDS, SCHEDULE, 4);

        assertEquals(first.pattern(), second.pattern());
        assertEquals(first.seedIndex(), second.seedIndex());
    }

    @Test
    void thePatternDoesNotDependOnHowManyThreadsSolvedIt() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();

        ToroidalSizedPatternGenerator.Generated oneThread = ToroidalSizedPatternGenerator.generate(table, grid, model(table), 31L, STEPS_PER_SEED, MAX_SEEDS, SCHEDULE, 1);
        ToroidalSizedPatternGenerator.Generated manyThreads = ToroidalSizedPatternGenerator.generate(table, grid, model(table), 31L, STEPS_PER_SEED, MAX_SEEDS, SCHEDULE, 8);

        assertEquals(oneThread.pattern(), manyThreads.pattern());
    }

    /** Independent worlds must not collapse onto one pattern: across a handful of world seeds at least two distinct patterns must appear. */
    @Test
    void differentWorldSeedsGiveDifferentPatterns() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();

        ToroidalSizedPatternGenerator.Generated first = ToroidalSizedPatternGenerator.generate(table, grid, model(table), 1L, STEPS_PER_SEED, MAX_SEEDS, SCHEDULE, 4);
        ToroidalSizedPatternGenerator.Generated second = ToroidalSizedPatternGenerator.generate(table, grid, model(table), 2L, STEPS_PER_SEED, MAX_SEEDS, SCHEDULE, 4);

        assertNotEquals(first.pattern(), second.pattern());
    }

    /** The oracle: replay attempts one at a time. The reported attempt must reproduce the pattern, and every earlier attempt must have failed - that is what "lowest solving index wins" means. */
    @Test
    void theReportedAttemptReproducesThePatternAndEveryEarlierAttemptFailed() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();
        long worldSeed = 9_001L;

        ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(table, grid, model(table), worldSeed, 3_000, MAX_SEEDS, SCHEDULE, 4);

        long baseSeed = ToroidalSizedPatternGenerator.baseSeedOf(worldSeed);
        ToroidalSizedLocalSearch.Result replay = ToroidalSizedLocalSearch.search(table, grid, model(table), baseSeed + generated.seedIndex(), 3_000, SCHEDULE);
        assertEquals(generated.pattern(), replay.assignment());
        for (int earlier = 0; earlier < generated.seedIndex(); earlier++) {
            assertEquals(null, ToroidalSizedLocalSearch.search(table, grid, model(table), baseSeed + earlier, 3_000, SCHEDULE).assignment(), "attempt " + earlier + " should have failed");
        }
    }

    @Test
    void worldSeedsThatAreNeighborsStillGetUnrelatedAttemptStreams() {
        long first = ToroidalSizedPatternGenerator.baseSeedOf(1L);
        long second = ToroidalSizedPatternGenerator.baseSeedOf(2L);

        assertTrue(Math.abs(first - second) > 1_000_000L, "neighboring world seeds must not produce overlapping seed ranges: " + first + " vs " + second);
    }

    /** All the context needed to reproduce a failure goes in the message: no logger in this module, and a crash report is all a player will send. */
    @Test
    void failureMessageCarriesTheWorldSeedTheBudgetAndWhatTheClosestAttemptWasStuckOn() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = fullyLegalTableWithWidths(1500, 1500, A, B, C);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class, () -> ToroidalSizedPatternGenerator.generate(table, grid, model(table), 424242L, 20_000, 3, SCHEDULE, 2)
        );

        String message = failure.getMessage();
        assertTrue(message.contains("424242"), message);
        assertTrue(message.contains("3 seeds"), message);
        assertTrue(message.contains("20000"), message);
        assertTrue(message.contains("6x6"), message);
        assertTrue(message.contains("closest"), message);
    }

    @Test
    void rejectsNonPositiveArguments() {
        ToroidalGrid grid = new ToroidalGrid(6, 6);
        RawRuleTable table = solvableTable();

        assertThrows(IllegalArgumentException.class, () -> ToroidalSizedPatternGenerator.generate(table, grid, model(table), 1L, 0, MAX_SEEDS, SCHEDULE, 2));
        assertThrows(IllegalArgumentException.class, () -> ToroidalSizedPatternGenerator.generate(table, grid, model(table), 1L, STEPS_PER_SEED, 0, SCHEDULE, 2));
        assertThrows(IllegalArgumentException.class, () -> ToroidalSizedPatternGenerator.generate(table, grid, model(table), 1L, STEPS_PER_SEED, MAX_SEEDS, SCHEDULE, 0));
    }
}
