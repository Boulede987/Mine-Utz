package com.mineutze.worldgen.plan;

/**
 * World-seed failure odds from per-attempt stage rates. Coarse, refinement retried; render once.
 * {@code refinementAttemptsPerWorld} 0 = no fine stage: rate still validated, unused.
 */
record ProductionFailureModel(int coarseAttemptsPerWorld, int refinementAttemptsPerWorld) {

    ProductionFailureModel {
        if (coarseAttemptsPerWorld <= 0 || refinementAttemptsPerWorld < 0) {
            throw new IllegalArgumentException(
                "ProductionFailureModel: need at least 1 coarse attempt and no negative refinement attempts, got " + coarseAttemptsPerWorld + " and " + refinementAttemptsPerWorld
            );
        }
    }

    static ProductionFailureModel fromProduction(PlanSettings settings) {
        PlanSettings.FineResolution fine = settings.fineResolution();
        return new ProductionFailureModel(settings.maxPatternAttempts(), fine == null ? 0 : fine.maxRefinementAttempts());
    }

    boolean hasRefinementStage() {
        return refinementAttemptsPerWorld > 0;
    }

    double worldFailureProbability(double coarseRate, double refinementRate, double realizationRate) {
        requireRate("coarse", coarseRate);
        requireRate("refinement", refinementRate);
        requireRate("realization", realizationRate);
        double coarseSucceeds = atLeastOneSuccess(coarseRate, coarseAttemptsPerWorld);
        double refinementSucceeds = hasRefinementStage() ? atLeastOneSuccess(refinementRate, refinementAttemptsPerWorld) : 1.0;
        return 1.0 - coarseSucceeds * refinementSucceeds * realizationRate;
    }

    /** Odds every allowed attempt of {@code stage} fails, at per-attempt success {@code rate}. */
    double stageFailure(AttemptResult.Stage stage, double rate) {
        requireRate(stage.name(), rate);
        return switch (stage) {
            case COARSE -> 1.0 - atLeastOneSuccess(rate, coarseAttemptsPerWorld);
            case REFINEMENT -> hasRefinementStage() ? 1.0 - atLeastOneSuccess(rate, refinementAttemptsPerWorld) : 0.0;
            case REALIZATION -> 1.0 - rate;
        };
    }

    private static double atLeastOneSuccess(double rate, int attempts) {
        return 1.0 - Math.pow(1.0 - rate, attempts);
    }

    private static void requireRate(String stage, double rate) {
        if (!(rate >= 0.0 && rate <= 1.0)) {
            throw new IllegalArgumentException("ProductionFailureModel: " + stage + " rate must be within 0..1, got " + rate);
        }
    }
}
