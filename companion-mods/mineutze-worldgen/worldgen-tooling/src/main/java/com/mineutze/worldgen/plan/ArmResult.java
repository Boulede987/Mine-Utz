package com.mineutze.worldgen.plan;

import java.util.Objects;
import java.util.Optional;

/** Verify arm: measured coarse solve rate, or the generator's refusal. {@code improves}: see SolveRateTrial.improves. */
record ArmResult(String label, Optional<StageEvidence> evidence, Optional<String> rejection, boolean improves) {

    ArmResult {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(rejection, "rejection");
        if (evidence.isPresent() == rejection.isPresent() || (improves && evidence.isEmpty())) {
            throw new IllegalArgumentException("ArmResult: an arm has either evidence or a rejection, and only measured arms can improve; got " + label);
        }
    }
}
