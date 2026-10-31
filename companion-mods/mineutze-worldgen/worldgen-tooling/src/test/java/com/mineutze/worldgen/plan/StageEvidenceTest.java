package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageEvidenceTest {

    @Test
    void startsWithNoTrials() {
        StageEvidence empty = StageEvidence.EMPTY;

        assertEquals(0, empty.trials());
        assertEquals(0, empty.successes());
    }

    @Test
    void countsSolvedAndUnsolvedAttempts() {
        StageEvidence evidence = StageEvidence.EMPTY.withSuccess().withFailure(OptionalInt.of(3)).withSuccess();

        assertEquals(3, evidence.trials());
        assertEquals(2, evidence.successes());
        assertEquals(1, evidence.failures());
    }

    @Test
    void keepsTheLowestCostOfAnyFailedAttempt() {
        StageEvidence evidence = StageEvidence.EMPTY.withFailure(OptionalInt.of(7)).withFailure(OptionalInt.of(2)).withFailure(OptionalInt.of(5));

        assertEquals(OptionalInt.of(2), evidence.closestFailedCost());
    }

    @Test
    void hasNoClosestFailedCostWithoutAFailure() {
        StageEvidence evidence = StageEvidence.EMPTY.withSuccess();

        assertEquals(OptionalInt.empty(), evidence.closestFailedCost());
    }

    @Test
    void aFailureWithoutACostKeepsTheClosestCostOfEarlierFailures() {
        StageEvidence evidence = StageEvidence.EMPTY.withFailure(OptionalInt.of(4)).withFailure(OptionalInt.empty());

        assertEquals(OptionalInt.of(4), evidence.closestFailedCost());
    }

    @Test
    void rejectsAClosestFailedCostWithoutAnyFailure() {
        assertThrows(IllegalArgumentException.class, () -> new StageEvidence(2, 2, OptionalInt.of(1)));
    }

    @Test
    void rejectsMoreSuccessesThanTrials() {
        assertThrows(IllegalArgumentException.class, () -> new StageEvidence(1, 2, OptionalInt.empty()));
    }
}
