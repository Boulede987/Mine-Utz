package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityEstimateTest {

    private static final ProductionFailureModel PRODUCTION = new ProductionFailureModel(200, 5);
    private static final double CONFIDENCE = 0.99;

    private static StageEvidence evidence(int successes, int trials) {
        return new StageEvidence(trials, successes, successes == trials ? OptionalInt.empty() : OptionalInt.of(1));
    }

    @Test
    void strongEvidenceBoundsWorldFailureNearZero() {
        Evidence strong = new Evidence(evidence(50, 50), evidence(50, 50), evidence(3, 3));

        ReliabilityEstimate estimate = ReliabilityEstimate.of(strong, PRODUCTION, CONFIDENCE);

        assertTrue(estimate.worldFailureHigh() < 0.01, estimate.toString());
    }

    @Test
    void noEvidenceLeavesTheWholeRangeOpen() {
        ReliabilityEstimate estimate = ReliabilityEstimate.of(Evidence.EMPTY, PRODUCTION, CONFIDENCE);

        assertEquals(0.0, estimate.worldFailureLow());
        assertEquals(1.0, estimate.worldFailureHigh());
    }

    @Test
    void lowNeverExceedsHighWhenEveryRenderWorked() {
        Evidence mixed = new Evidence(evidence(3, 40), evidence(4, 6), evidence(1, 1));

        ReliabilityEstimate estimate = ReliabilityEstimate.of(mixed, PRODUCTION, CONFIDENCE);

        assertTrue(estimate.worldFailureLow() <= estimate.worldFailureHigh(), estimate.toString());
    }

    @Test
    void manyFailedCoarseAttemptsRaiseTheLowerBound() {
        Evidence hopeless = new Evidence(evidence(0, 400), StageEvidence.EMPTY, StageEvidence.EMPTY);

        ReliabilityEstimate estimate = ReliabilityEstimate.of(hopeless, PRODUCTION, CONFIDENCE);

        assertTrue(estimate.worldFailureLow() > 0.05, estimate.toString());
    }
}
