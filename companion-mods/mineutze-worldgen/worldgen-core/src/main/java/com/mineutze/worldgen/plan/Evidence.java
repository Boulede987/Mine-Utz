package com.mineutze.worldgen.plan;

import java.util.Objects;

record Evidence(StageEvidence coarse, StageEvidence refinement, StageEvidence realization) {

    static final Evidence EMPTY = new Evidence(StageEvidence.EMPTY, StageEvidence.EMPTY, StageEvidence.EMPTY);

    Evidence {
        Objects.requireNonNull(coarse, "coarse");
        Objects.requireNonNull(refinement, "refinement");
        Objects.requireNonNull(realization, "realization");
    }

    Evidence with(AttemptResult result) {
        return switch (result.stage()) {
            case COARSE -> new Evidence(updated(coarse, result), refinement, realization);
            case REFINEMENT -> new Evidence(coarse, updated(refinement, result), realization);
            case REALIZATION -> new Evidence(coarse, refinement, updated(realization, result));
        };
    }

    int totalTrials() {
        return coarse.trials() + refinement.trials() + realization.trials();
    }

    private static StageEvidence updated(StageEvidence stage, AttemptResult result) {
        return result.solved() ? stage.withSuccess() : stage.withFailure(result.failedCost());
    }
}
