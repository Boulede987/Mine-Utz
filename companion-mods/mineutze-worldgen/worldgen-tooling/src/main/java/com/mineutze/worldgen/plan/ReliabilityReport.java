package com.mineutze.worldgen.plan;

import java.time.Duration;
import java.util.Locale;

/** What a Reliability run measured: verdict, range, the attempts it rests on, and the failures ranked for Verify. */
public record ReliabilityReport(ReliabilityVerdict verdict, ReliabilityEstimate estimate, Evidence evidence, StageTightness tightness, double targetWorldFailure,
    Duration elapsed) {

    private static final double MILLIS_PER_SECOND = 1000.0;

    public String summary() {
        return String.format(Locale.ROOT, "Reliability run (measured, not a proof): %.1f s; attempts solved: coarse %s, refinement %s, render %s.",
            elapsed.toMillis() / MILLIS_PER_SECOND, evidence.coarse().describe(), evidence.refinement().describe(), evidence.realization().describe())
            + "\n" + rangeLine(estimate, targetWorldFailure, verdict) + "\n" + estimate.describeBasis(evidence, targetWorldFailure)
            + FeasibilityVerdict.tightnessLines("coarse", tightness.coarse()) + FeasibilityVerdict.tightnessLines("refinement", tightness.refinement());
    }

    static String rangeLine(ReliabilityEstimate estimate, double targetWorldFailure, ReliabilityVerdict verdict) {
        return "A world seed fails between " + FeasibilityVerdict.percent(estimate.worldFailureLow()) + " and " + FeasibilityVerdict.percent(estimate.worldFailureHigh())
            + " of the time (render not counted in the upper figure); target " + FeasibilityVerdict.percent(targetWorldFailure) + ": " + verdict.label();
    }
}
