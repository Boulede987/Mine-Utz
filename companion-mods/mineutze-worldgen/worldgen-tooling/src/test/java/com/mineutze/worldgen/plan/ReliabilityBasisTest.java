package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reliability line must say what it rests on: the user read a sample-size jump as a worse graph. */
class ReliabilityBasisTest {

    private static final ProductionFailureModel FIVE_RETRIES_EACH = new ProductionFailureModel(5, 5);
    private static final double CONFIDENCE = 0.99;
    private static final double TARGET = 0.01;

    private static Evidence evidence(int coarseTrials, int refinementTrials) {
        return new Evidence(new StageEvidence(coarseTrials, coarseTrials, OptionalInt.empty()), new StageEvidence(refinementTrials, refinementTrials, OptionalInt.empty()),
            new StageEvidence(1, 1, OptionalInt.empty()));
    }

    @Test
    void fewRefinementAttemptsDriveTheUpperFigure() {
        ReliabilityEstimate estimate = ReliabilityEstimate.of(evidence(60, 3), FIVE_RETRIES_EACH, CONFIDENCE);

        assertEquals(AttemptResult.Stage.REFINEMENT, estimate.drivingStage());
    }

    @Test
    void fewCoarseAttemptsDriveTheUpperFigure() {
        ReliabilityEstimate estimate = ReliabilityEstimate.of(evidence(3, 60), FIVE_RETRIES_EACH, CONFIDENCE);

        assertEquals(AttemptResult.Stage.COARSE, estimate.drivingStage());
    }

    @Test
    void theBasisNamesTheAttemptCountsAndTheDrivingStage() {
        Evidence evidence = evidence(6, 3);
        ReliabilityEstimate estimate = ReliabilityEstimate.of(evidence, FIVE_RETRIES_EACH, CONFIDENCE);

        String basis = estimate.describeBasis(evidence, TARGET);

        assertTrue(basis.contains("coarse 6") && basis.contains("refinement 3"), basis);
        assertTrue(basis.contains("mostly from refinement"), basis);
        assertTrue(basis.contains("Reliability run"), basis);
    }

    @Test
    void aDecidedRangeDoesNotSuggestALongerRun() {
        Evidence evidence = evidence(2000, 2000);
        ReliabilityEstimate estimate = ReliabilityEstimate.of(evidence, FIVE_RETRIES_EACH, CONFIDENCE);

        String basis = estimate.describeBasis(evidence, TARGET);

        assertFalse(basis.contains("Reliability run"), basis);
    }

    @Test
    void theStageFailureIsAllRetriesFailing() {
        assertEquals(0.5 * 0.5 * 0.5 * 0.5 * 0.5, FIVE_RETRIES_EACH.stageFailure(AttemptResult.Stage.REFINEMENT, 0.5), 1e-12);
    }
}
