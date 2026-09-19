package com.mineutze.worldgen.coherence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SolveStatisticsTest {

    private static final long UNSOLVED = SolveStatistics.UNSOLVED;

    @Test
    void countsTrialsAndSolvedTrials() {
        SolveStatistics statistics = new SolveStatistics(new long[] {10, UNSOLVED, 30, UNSOLVED, 50});

        assertEquals(5, statistics.trialCount());
        assertEquals(3, statistics.solvedCount());
    }

    @Test
    void solvedFractionWithinABudgetCountsTrialsThatSolvedInThatManyStepsOrFewer() {
        SolveStatistics statistics = new SolveStatistics(new long[] {10, UNSOLVED, 30, UNSOLVED, 50});

        assertEquals(0.2, statistics.solvedFractionWithin(10), 1e-12);
        assertEquals(0.4, statistics.solvedFractionWithin(30), 1e-12);
        assertEquals(0.6, statistics.solvedFractionWithin(1_000), 1e-12);
    }

    /** Several trials needing exactly the same number of steps must all be counted at that budget, not just one of them. */
    @Test
    void trialsTiedOnTheSameStepCountAreAllCounted() {
        SolveStatistics statistics = new SolveStatistics(new long[] {10, 10, 10, 30});

        assertEquals(0.75, statistics.solvedFractionWithin(10), 1e-12);
    }

    @Test
    void anUnsolvedTrialIsNeverCountedHoweverLargeTheBudget() {
        SolveStatistics statistics = new SolveStatistics(new long[] {UNSOLVED, UNSOLVED});

        assertEquals(0.0, statistics.solvedFractionWithin(Long.MAX_VALUE), 1e-12);
    }

    /** Percentiles are over the trials that solved, ordered by steps: with steps 10, 30, 50 the median is 30. */
    @Test
    void percentileOfStepsAmongSolvedTrials() {
        SolveStatistics statistics = new SolveStatistics(new long[] {50, UNSOLVED, 10, 30});

        assertEquals(10, statistics.stepsAtPercentile(0.0));
        assertEquals(30, statistics.stepsAtPercentile(0.5));
        assertEquals(50, statistics.stepsAtPercentile(1.0));
    }

    /** No failures in 100 trials still leaves a real chance of failure; the upper end of the 95% Wilson interval is about 3.7%. */
    @Test
    void noObservedFailuresStillLeavesAnUpperBoundOnTheFailureProbability() {
        long[] allSolved = new long[100];
        java.util.Arrays.fill(allSolved, 5L);

        SolveStatistics statistics = new SolveStatistics(allSolved);

        assertEquals(0.037, statistics.failureProbabilityUpperBound(1_000), 1e-3);
    }

    @Test
    void moreTrialsWithNoFailuresTightenTheUpperBound() {
        long[] hundred = new long[100];
        long[] thousand = new long[1000];
        java.util.Arrays.fill(hundred, 5L);
        java.util.Arrays.fill(thousand, 5L);

        assertEquals(true, new SolveStatistics(thousand).failureProbabilityUpperBound(1_000) < new SolveStatistics(hundred).failureProbabilityUpperBound(1_000));
    }

    @Test
    void theUpperBoundIsAboveTheObservedFailureRate() {
        SolveStatistics statistics = new SolveStatistics(new long[] {5, UNSOLVED, 5, 5, 5, 5, 5, 5, 5, 5});

        assertEquals(true, statistics.failureProbabilityUpperBound(1_000) > 0.1);
    }

    @Test
    void aBudgetSmallerThanEveryTrialMakesEveryTrialAFailure() {
        SolveStatistics statistics = new SolveStatistics(new long[] {100, 200});

        assertEquals(true, statistics.failureProbabilityUpperBound(50) > 0.99);
    }

    /** With a per-seed failure bound of 0.037 (100 clean trials), five independent seeds bring failure under one in a million: 0.037^5 = 6.9e-8 while 0.037^4 = 1.9e-6. */
    @Test
    void restartsNeededForATargetFailureProbability() {
        long[] allSolved = new long[100];
        java.util.Arrays.fill(allSolved, 5L);

        assertEquals(5, new SolveStatistics(allSolved).restartsForFailureBelow(1e-6, 1_000));
    }

    @Test
    void restartsNeededGrowsAsTheTargetGetsStricter() {
        long[] allSolved = new long[100];
        java.util.Arrays.fill(allSolved, 5L);
        SolveStatistics statistics = new SolveStatistics(allSolved);

        assertEquals(true, statistics.restartsForFailureBelow(1e-12, 1_000) > statistics.restartsForFailureBelow(1e-6, 1_000));
    }

    @Test
    void noRestartCountHelpsWhenNoTrialSolvedWithinTheBudget() {
        SolveStatistics statistics = new SolveStatistics(new long[] {UNSOLVED, UNSOLVED, UNSOLVED});

        assertThrows(IllegalStateException.class, () -> statistics.restartsForFailureBelow(1e-6, 1_000));
    }

    @Test
    void rejectsNoTrials() {
        assertThrows(IllegalArgumentException.class, () -> new SolveStatistics(new long[0]));
    }

    @Test
    void rejectsATargetOutsideZeroAndOne() {
        SolveStatistics statistics = new SolveStatistics(new long[] {5});

        assertThrows(IllegalArgumentException.class, () -> statistics.restartsForFailureBelow(0.0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> statistics.restartsForFailureBelow(1.0, 1_000));
    }

    @Test
    void percentileOutsideZeroToOneIsRejected() {
        SolveStatistics statistics = new SolveStatistics(new long[] {5});

        assertThrows(IllegalArgumentException.class, () -> statistics.stepsAtPercentile(1.5));
    }

    @Test
    void percentileOfNothingSolvedIsRejectedInsteadOfInventingANumber() {
        SolveStatistics statistics = new SolveStatistics(new long[] {UNSOLVED});

        assertThrows(IllegalStateException.class, () -> statistics.stepsAtPercentile(0.5));
    }
}
