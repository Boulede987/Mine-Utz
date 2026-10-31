package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionFailureModelTest {

    private static final double TOLERANCE = 1e-12;

    @Test
    void certainStagesNeverFailAWorld() {
        ProductionFailureModel model = new ProductionFailureModel(200, 5);

        assertEquals(0.0, model.worldFailureProbability(1.0, 1.0, 1.0), TOLERANCE);
    }

    @Test
    void anUnsolvableCoarseStageFailsEveryWorld() {
        ProductionFailureModel model = new ProductionFailureModel(200, 5);

        assertEquals(1.0, model.worldFailureProbability(0.0, 1.0, 1.0), TOLERANCE);
    }

    @Test
    void aRenderThatNeverWorksFailsEveryWorld() {
        ProductionFailureModel model = new ProductionFailureModel(200, 5);

        assertEquals(1.0, model.worldFailureProbability(1.0, 1.0, 0.0), TOLERANCE);
    }

    /** 2 coarse tries at 1/2 solve with probability 3/4, 1 refinement try at 1/2: world succeeds 3/8 of the time. */
    @Test
    void combinesRetriedStagesAsIndependentTries() {
        ProductionFailureModel model = new ProductionFailureModel(2, 1);

        assertEquals(0.625, model.worldFailureProbability(0.5, 0.5, 1.0), TOLERANCE);
    }

    @Test
    void withoutARefinementStageItsRateIsIgnored() {
        ProductionFailureModel model = new ProductionFailureModel(2, 0);

        assertEquals(0.25, model.worldFailureProbability(0.5, 0.0, 1.0), TOLERANCE);
    }

    @Test
    void readsTheRetryCountsProductionActuallyUses() {
        ProductionFailureModel model = ProductionFailureModel.fromProduction(PlanSettings.DEFAULT);

        assertEquals(PlanSettings.DEFAULT.maxPatternAttempts(), model.coarseAttemptsPerWorld());
        assertEquals(PlanSettings.DEFAULT.fineResolution().maxRefinementAttempts(), model.refinementAttemptsPerWorld());
    }

    @Test
    void settingsWithoutFineResolutionHaveNoRefinementStage() {
        PlanSettings direct = new PlanSettings(8, 300, 1.3, new double[] {20, 0}, 208, 0.10, 3_000_000, 60, ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE);

        assertEquals(0, ProductionFailureModel.fromProduction(direct).refinementAttemptsPerWorld());
    }

    @Test
    void rejectsARateOutsideTheUnitInterval() {
        ProductionFailureModel model = new ProductionFailureModel(200, 5);

        assertThrows(IllegalArgumentException.class, () -> model.worldFailureProbability(1.2, 1.0, 1.0));
    }

    @Test
    void rejectsANonPositiveCoarseAttemptCount() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionFailureModel(0, 5));
    }
}
