package com.mineutze.worldgen.plan;

/** Seed-failure range against the target: decided only when the whole range is on one side. */
public enum ReliabilityVerdict {
    MET,
    NOT_MET,
    UNDECIDED;

    /** As the editor shows it: "NOT MET", not "NOT_MET". */
    public String label() {
        return name().replace('_', ' ');
    }

    static ReliabilityVerdict of(ReliabilityEstimate estimate, double targetWorldFailure) {
        if (estimate.worldFailureHigh() <= targetWorldFailure) {
            return MET;
        }
        return estimate.worldFailureLow() > targetWorldFailure ? NOT_MET : UNDECIDED;
    }
}
