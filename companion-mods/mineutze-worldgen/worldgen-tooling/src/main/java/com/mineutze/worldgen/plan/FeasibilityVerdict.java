package com.mineutze.worldgen.plan;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Confirmation, never a guess (user requirement 2026-10-31): FEASIBLE carries the world it built, INFEASIBLE its
 * proof, UNPROVEN only plain facts. {@code reliability} = secondary production odds, shown only next to a built world:
 * with no world its range (e.g. 0%..100%) says nothing and reads like a guess.
 */
public record FeasibilityVerdict(
    Outcome outcome, Optional<InfeasibilityProof> proof, Optional<BuiltWorld> builtWorld, Evidence evidence, Optional<ReliabilityEstimate> reliability,
    double targetWorldFailure, Duration elapsed
) {

    public enum Outcome {
        FEASIBLE,
        INFEASIBLE,
        UNPROVEN
    }

    private static final double PERCENT_PER_UNIT = 100.0;
    private static final double SMALLEST_SHOWN_PERCENT = 0.001;
    private static final double MILLIS_PER_SECOND = 1000.0;

    public FeasibilityVerdict {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(builtWorld, "builtWorld");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(reliability, "reliability");
        Objects.requireNonNull(elapsed, "elapsed");
        boolean feasible = outcome == Outcome.FEASIBLE;
        if ((outcome == Outcome.INFEASIBLE) != proof.isPresent() || feasible != builtWorld.isPresent() || (!feasible && reliability.isPresent())) {
            throw new IllegalArgumentException(
                "FeasibilityVerdict: INFEASIBLE needs exactly a proof, FEASIBLE exactly a built world, reliability only with a built world; got " + outcome
                    + ", proof present=" + proof.isPresent() + ", world present=" + builtWorld.isPresent() + ", reliability present=" + reliability.isPresent()
            );
        }
        if (!(targetWorldFailure > 0.0 && targetWorldFailure < 1.0) || elapsed.isNegative()) {
            throw new IllegalArgumentException("FeasibilityVerdict: target must be strictly between 0 and 1, elapsed not negative; got " + targetWorldFailure + ", " + elapsed);
        }
    }

    static FeasibilityVerdict infeasible(InfeasibilityProof proof, Evidence evidence, double targetWorldFailure, Duration elapsed) {
        return new FeasibilityVerdict(Outcome.INFEASIBLE, Optional.of(proof), Optional.empty(), evidence, Optional.empty(), targetWorldFailure, elapsed);
    }

    static FeasibilityVerdict feasible(BuiltWorld world, Evidence evidence, ReliabilityEstimate reliability, double targetWorldFailure, Duration elapsed) {
        return new FeasibilityVerdict(Outcome.FEASIBLE, Optional.empty(), Optional.of(world), evidence, Optional.of(reliability), targetWorldFailure, elapsed);
    }

    static FeasibilityVerdict unproven(Evidence evidence, double targetWorldFailure, Duration elapsed) {
        return new FeasibilityVerdict(Outcome.UNPROVEN, Optional.empty(), Optional.empty(), evidence, Optional.empty(), targetWorldFailure, elapsed);
    }

    public String summary() {
        return switch (outcome) {
            case FEASIBLE -> feasibleSummary();
            case INFEASIBLE -> infeasibleSummary(proof.orElseThrow());
            case UNPROVEN -> unprovenSummary();
        };
    }

    private String feasibleSummary() {
        BuiltWorld world = builtWorld.orElseThrow();
        return "FEASIBLE: built a world after " + world.attemptsUntilBuilt() + " attempts, in " + seconds() + " s.\n" + attemptsLine() + reliabilityLine();
    }

    private static String infeasibleSummary(InfeasibilityProof proof) {
        if (proof.generatorRejection().isPresent()) {
            return "INFEASIBLE: the world generator rejects this graph: " + proof.generatorRejection().get();
        }
        List<LawConflict> conflicts = proof.conflicts();
        if (conflicts.isEmpty()) {
            return "INFEASIBLE: the laws contradict each other (proven); naming which ones ran out of time.";
        }
        StringBuilder summary = new StringBuilder("INFEASIBLE: these laws cannot all hold - " + conflicts.size()
            + " independent conflict(s); changing any one law of a conflict removes that conflict.");
        for (int index = 0; index < conflicts.size(); index++) {
            appendConflict(summary, index + 1, conflicts.get(index));
        }
        if (!proof.explanationComplete()) {
            summary.append("\n(time ran out while explaining: more conflicts may exist - fix these and check again)");
        }
        return summary.toString();
    }

    private static void appendConflict(StringBuilder summary, int number, LawConflict conflict) {
        summary.append("\nConflict ").append(number).append(':');
        conflict.describeLines().forEach(line -> summary.append("\n  - ").append(line));
    }

    private String unprovenSummary() {
        return "UNPROVEN: no world built and no contradiction found in " + seconds() + " s.\n" + attemptsLine();
    }

    private String attemptsLine() {
        return "Attempts solved: coarse " + evidence.coarse().describe() + ", refinement " + evidence.refinement().describe() + ", render "
            + evidence.realization().describe() + ".";
    }

    private String reliabilityLine() {
        return reliability.map(estimate -> "\nProduction reliability (secondary): a world seed fails between " + percent(estimate.worldFailureLow()) + " and "
            + percent(estimate.worldFailureHigh()) + " of the time (render not counted in the upper figure); target " + percent(targetWorldFailure) + ".").orElse("");
    }

    private String seconds() {
        return String.format(Locale.ROOT, "%.1f", elapsed.toMillis() / MILLIS_PER_SECOND);
    }

    private static String percent(double probability) {
        double percent = probability * PERCENT_PER_UNIT;
        if (percent > 0.0 && percent < SMALLEST_SHOWN_PERCENT) {
            return "< " + SMALLEST_SHOWN_PERCENT + "%";
        }
        return String.format(Locale.ROOT, "%.3f%%", percent);
    }
}
