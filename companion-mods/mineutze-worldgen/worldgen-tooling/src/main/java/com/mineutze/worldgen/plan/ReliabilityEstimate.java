package com.mineutze.worldgen.plan;

/**
 * Secondary info: range of production world-seed failure odds the evidence allows. High end takes render as certain -
 * render runs once per world, cannot be certified in a short run (DECISIONS.md 2026-10-31).
 */
record ReliabilityEstimate(double worldFailureLow, double worldFailureHigh) {

    private static final double CERTAIN = 1.0;

    static ReliabilityEstimate of(Evidence evidence, ProductionFailureModel model, double confidence) {
        double low = model.worldFailureProbability(
            upperRate(evidence.coarse(), confidence), upperRate(evidence.refinement(), confidence), upperRate(evidence.realization(), confidence)
        );
        double high = model.worldFailureProbability(lowerRate(evidence.coarse(), confidence), lowerRate(evidence.refinement(), confidence), CERTAIN);
        return new ReliabilityEstimate(low, high);
    }

    private static double lowerRate(StageEvidence stage, double confidence) {
        return BinomialConfidence.lowerBound(stage.successes(), stage.trials(), confidence);
    }

    private static double upperRate(StageEvidence stage, double confidence) {
        return BinomialConfidence.upperBound(stage.successes(), stage.trials(), confidence);
    }
}
