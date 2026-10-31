package com.mineutze.worldgen.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Why no world can exist. {@code generatorRejection}: generator's own refusal. Else {@code conflicts}: independent
 * minimal conflicts found; {@code explanationComplete} false = time ran out, more conflicts may exist (the contradiction
 * itself is proven either way).
 */
public record InfeasibilityProof(List<LawConflict> conflicts, Optional<String> generatorRejection, boolean explanationComplete) {

    public InfeasibilityProof {
        Objects.requireNonNull(conflicts, "conflicts");
        Objects.requireNonNull(generatorRejection, "generatorRejection");
        conflicts = List.copyOf(conflicts);
    }

    static InfeasibilityProof rejectedByGenerator(String message) {
        return new InfeasibilityProof(List.of(), Optional.of(message), true);
    }

    static InfeasibilityProof of(ConflictExplainer.Explanation explanation) {
        return new InfeasibilityProof(explanation.conflicts(), Optional.empty(), explanation.complete());
    }
}
