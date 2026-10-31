package com.mineutze.worldgen.plan;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code rejection}: generator's own message when it rejects the graph outright. {@code closestFailureReport}: size slack +
 * neighborhood of lowest-cost failed coarse attempt. {@code renderFailureReport}: why first failed render failed.
 * Lower bound may exceed certified upper bound: upper ignores render (see {@link SequentialVerdictRule}).
 */
public record FeasibilityVerdict(
    Outcome outcome, Evidence evidence, double worldFailureLowerBound, double certifiedWorldFailureUpperBound, Duration elapsed, Optional<String> rejection,
    Optional<String> closestFailureReport, Optional<String> renderFailureReport
) {

    public enum Outcome {
        PASS,
        FAIL,
        BORDERLINE
    }

    private static final double CERTAIN_FAILURE = 1.0;
    private static final double PERCENT_PER_UNIT = 100.0;
    private static final double SMALLEST_SHOWN_PERCENT = 0.001;
    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final String RENDER_CAVEAT = "render not certified: runs once per world, see DECISIONS.md 2026-10-31";

    public FeasibilityVerdict {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(rejection, "rejection");
        Objects.requireNonNull(closestFailureReport, "closestFailureReport");
        Objects.requireNonNull(renderFailureReport, "renderFailureReport");
        requireProbability("worldFailureLowerBound", worldFailureLowerBound);
        requireProbability("certifiedWorldFailureUpperBound", certifiedWorldFailureUpperBound);
    }

    static FeasibilityVerdict rejected(String rejection, Duration elapsed) {
        return new FeasibilityVerdict(
            Outcome.FAIL, Evidence.EMPTY, CERTAIN_FAILURE, CERTAIN_FAILURE, elapsed, Optional.of(rejection), Optional.empty(), Optional.empty()
        );
    }

    public String summary() {
        if (rejection.isPresent()) {
            return "FAIL: the world generator rejects this graph, nothing was solved: " + rejection.get();
        }
        StringBuilder summary = new StringBuilder(headline()).append('\n').append(evidenceLine());
        if (outcome != Outcome.PASS) {
            closestFailureReport.ifPresent(report -> summary.append("\nClosest failed coarse attempt:\n").append(report));
            renderFailureReport.ifPresent(report -> summary.append("\nFirst failed render:\n").append(report));
        }
        return summary.toString();
    }

    private String headline() {
        return switch (outcome) {
            case PASS -> "PASS: a world seed fails at most " + percent(certifiedWorldFailureUpperBound) + " of the time (coarse + refinement certified).";
            case FAIL -> "FAIL: a world seed fails at least " + percent(worldFailureLowerBound) + " of the time.";
            case BORDERLINE -> "BORDERLINE: undecided in the time budget; a world seed fails between " + percent(worldFailureLowerBound) + " and "
                + percent(certifiedWorldFailureUpperBound) + " of the time (upper figure leaves out render).";
        };
    }

    private String evidenceLine() {
        return "Attempts solved: coarse " + evidence.coarse().describe() + ", refinement " + evidence.refinement().describe() + ", render "
            + evidence.realization().describe() + " (" + RENDER_CAVEAT + "). Took " + String.format(Locale.ROOT, "%.1f", elapsed.toMillis() / MILLIS_PER_SECOND) + " s.";
    }

    private static String percent(double probability) {
        double percent = probability * PERCENT_PER_UNIT;
        if (percent > 0.0 && percent < SMALLEST_SHOWN_PERCENT) {
            return "< " + SMALLEST_SHOWN_PERCENT + "%";
        }
        return String.format(Locale.ROOT, "%.3f%%", percent);
    }

    private static void requireProbability(String name, double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException("FeasibilityVerdict: " + name + " must be within 0..1, got " + value);
        }
    }
}
