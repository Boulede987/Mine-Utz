package com.mineutze.worldgen.plan;

import java.util.Arrays;

/**
 * What a batch of independent single-seed searches says about how reliably one seed solves
 * (2026-09-19). Each trial is the number of steps its seed needed, or {@link #UNSOLVED} if it ran
 * out of budget. "Every world seed must succeed" cannot be proven by a handful of lucky runs, so
 * this turns a batch into a bound: the per-seed failure probability, taken as the upper end of a
 * 95% Wilson score interval (so zero observed failures still leaves a real, quantified chance), and from
 * it how many independent restarts a world seed needs to push the overall failure below a target.
 *
 * <p>Restarts are independent because each uses a different random seed, so failure of K seeds is
 * the per-seed failure raised to the K.
 */
final class SolveStatistics {

    static final long UNSOLVED = -1;

    private static final double Z_FOR_95_PERCENT_TWO_SIDED = 1.96;

    private final int trialCount;
    private final long[] solvedSteps;

    SolveStatistics(long[] stepsToSolve) {
        if (stepsToSolve.length == 0) {
            throw new IllegalArgumentException("SolveStatistics: needs at least one trial");
        }
        this.trialCount = stepsToSolve.length;
        this.solvedSteps = Arrays.stream(stepsToSolve).filter(steps -> steps != UNSOLVED).sorted().toArray();
    }

    int trialCount() {
        return trialCount;
    }

    int solvedCount() {
        return solvedSteps.length;
    }

    /** Share of ALL trials (solved or not) that solved in {@code steps} steps or fewer. */
    double solvedFractionWithin(long steps) {
        return solvedWithin(steps) / (double) trialCount;
    }

    /** Steps needed by the solved trial at {@code quantile} (0 = fastest, 1 = slowest) among the trials that solved. */
    long stepsAtPercentile(double quantile) {
        if (quantile < 0.0 || quantile > 1.0) {
            throw new IllegalArgumentException("SolveStatistics: quantile must be within 0..1, got " + quantile);
        }
        if (solvedSteps.length == 0) {
            throw new IllegalStateException("SolveStatistics: no trial solved, so there is no step count to report");
        }
        int rank = (int) Math.ceil(quantile * solvedSteps.length) - 1;
        return solvedSteps[Math.max(0, rank)];
    }

    /** Upper end of the 95% Wilson score interval on the chance that one seed fails to solve within {@code budgetSteps}. */
    double failureProbabilityUpperBound(long budgetSteps) {
        int failures = trialCount - solvedWithin(budgetSteps);
        double observed = failures / (double) trialCount;
        double zSquared = Z_FOR_95_PERCENT_TWO_SIDED * Z_FOR_95_PERCENT_TWO_SIDED;
        double centre = observed + zSquared / (2.0 * trialCount);
        double spread = Z_FOR_95_PERCENT_TWO_SIDED * Math.sqrt(observed * (1.0 - observed) / trialCount + zSquared / (4.0 * trialCount * (double) trialCount));
        return Math.min(1.0, (centre + spread) / (1.0 + zSquared / trialCount));
    }

    /** Fewest independent seeds so that, at the failure bound above, all of them failing has probability below {@code targetFailureProbability}. */
    int restartsForFailureBelow(double targetFailureProbability, long budgetSteps) {
        if (targetFailureProbability <= 0.0 || targetFailureProbability >= 1.0) {
            throw new IllegalArgumentException("SolveStatistics: target failure probability must be within (0, 1), got " + targetFailureProbability);
        }
        if (solvedWithin(budgetSteps) == 0) {
            throw new IllegalStateException("SolveStatistics: no trial solved within " + budgetSteps + " steps, so no number of restarts is justified");
        }
        double perSeedFailure = failureProbabilityUpperBound(budgetSteps);
        if (perSeedFailure >= 1.0) {
            throw new IllegalStateException("SolveStatistics: the failure bound is 100% within " + budgetSteps + " steps, so no number of restarts is justified");
        }
        return Math.max(1, (int) Math.ceil(Math.log(targetFailureProbability) / Math.log(perSeedFailure)));
    }

    private int solvedWithin(long steps) {
        int index = Arrays.binarySearch(solvedSteps, steps);
        if (index >= 0) {
            while (index + 1 < solvedSteps.length && solvedSteps[index + 1] == steps) {
                index++;
            }
            return index + 1;
        }
        return -index - 1;
    }
}
