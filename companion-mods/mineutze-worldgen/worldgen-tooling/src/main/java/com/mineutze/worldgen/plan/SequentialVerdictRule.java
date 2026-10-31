package com.mineutze.worldgen.plan;

import java.util.Objects;
import java.util.Optional;

/**
 * PASS / FAIL / keep going, re-checked after every attempt - hence high {@code budget.confidence()}. PASS certifies
 * coarse + refinement only; render gates PASS (>= 1 trial, 0 failures) and counts toward FAIL. See DECISIONS.md 2026-10-31.
 */
final class SequentialVerdictRule {

    enum Deadline {
        REACHED,
        NOT_REACHED
    }

    private static final double CERTAIN = 1.0;

    private final ProductionFailureModel model;
    private final FeasibilityBudget budget;

    SequentialVerdictRule(ProductionFailureModel model, FeasibilityBudget budget) {
        this.model = Objects.requireNonNull(model, "model");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    Optional<FeasibilityVerdict.Outcome> decide(Evidence evidence, Deadline deadline) {
        if (worldFailureLowerBound(evidence) > budget.maxWorldFailureProbability()) {
            return Optional.of(FeasibilityVerdict.Outcome.FAIL);
        }
        if (certifiedWorldFailureUpperBound(evidence) <= budget.maxWorldFailureProbability() && everyRenderWorked(evidence.realization())) {
            return Optional.of(FeasibilityVerdict.Outcome.PASS);
        }
        return deadline == Deadline.REACHED ? Optional.of(FeasibilityVerdict.Outcome.BORDERLINE) : Optional.empty();
    }

    /** Worst case for retried stages, render taken as certain. */
    double certifiedWorldFailureUpperBound(Evidence evidence) {
        return model.worldFailureProbability(lowerRate(evidence.coarse()), lowerRate(evidence.refinement()), CERTAIN);
    }

    /** Best case for every stage, render included. */
    double worldFailureLowerBound(Evidence evidence) {
        return model.worldFailureProbability(upperRate(evidence.coarse()), upperRate(evidence.refinement()), upperRate(evidence.realization()));
    }

    private static boolean everyRenderWorked(StageEvidence realization) {
        return realization.trials() > 0 && realization.failures() == 0;
    }

    private double lowerRate(StageEvidence stage) {
        return BinomialConfidence.lowerBound(stage.successes(), stage.trials(), budget.confidence());
    }

    private double upperRate(StageEvidence stage) {
        return BinomialConfidence.upperBound(stage.successes(), stage.trials(), budget.confidence());
    }
}
