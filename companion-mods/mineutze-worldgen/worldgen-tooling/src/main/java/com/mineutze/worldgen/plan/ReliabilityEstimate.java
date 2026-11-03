package com.mineutze.worldgen.plan;

import java.util.Locale;
import java.util.Objects;

/**
 * Secondary info: range of production world-seed failure odds the evidence allows. High end takes render as certain -
 * render runs once per world, cannot be certified in a short run (DECISIONS.md 2026-10-31). {@code drivingStage}: the
 * retried stage whose few attempts weigh most in the high end.
 */
record ReliabilityEstimate(double worldFailureLow, double worldFailureHigh, AttemptResult.Stage drivingStage) {

    private static final double CERTAIN = 1.0;

    ReliabilityEstimate {
        Objects.requireNonNull(drivingStage, "drivingStage");
    }

    static ReliabilityEstimate of(Evidence evidence, ProductionFailureModel model, double confidence) {
        double coarseLow = lowerRate(evidence.coarse(), confidence);
        double refinementLow = lowerRate(evidence.refinement(), confidence);
        double low = model.worldFailureProbability(
            upperRate(evidence.coarse(), confidence), upperRate(evidence.refinement(), confidence), upperRate(evidence.realization(), confidence)
        );
        double high = model.worldFailureProbability(coarseLow, refinementLow, CERTAIN);
        AttemptResult.Stage driving = model.stageFailure(AttemptResult.Stage.REFINEMENT, refinementLow) > model.stageFailure(AttemptResult.Stage.COARSE, coarseLow)
            ? AttemptResult.Stage.REFINEMENT : AttemptResult.Stage.COARSE;
        return new ReliabilityEstimate(low, high, driving);
    }

    /** What the range rests on; past the target, which stage to measure longer. */
    String describeBasis(Evidence evidence, double targetWorldFailure) {
        String basis = "Based on coarse " + evidence.coarse().trials() + ", refinement " + evidence.refinement().trials() + " attempts"
            + (evidence.realization().trials() == 0 ? " and no rendered world (render failures not covered at all)" : "");
        if (worldFailureHigh <= targetWorldFailure) {
            return basis + ".";
        }
        StageEvidence driving = drivingStage == AttemptResult.Stage.REFINEMENT ? evidence.refinement() : evidence.coarse();
        return basis + "; the upper figure comes mostly from " + drivingStage.name().toLowerCase(Locale.ROOT) + "'s " + driving.trials()
            + " attempts - a longer Reliability run narrows it.";
    }

    private static double lowerRate(StageEvidence stage, double confidence) {
        return BinomialConfidence.lowerBound(stage.successes(), stage.trials(), confidence);
    }

    private static double upperRate(StageEvidence stage, double confidence) {
        return BinomialConfidence.upperBound(stage.successes(), stage.trials(), confidence);
    }
}
