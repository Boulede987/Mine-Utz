package com.mineutze.worldgen.plan;

import java.util.Objects;
import java.util.Optional;

/** One row of the editor's ranking table. Rank counts from 1, as shown. Empty loosening = Verify cannot measure it. */
public record RankingChoice(int rank, String law, int attemptsBroken, Optional<String> loosening) {

    public RankingChoice {
        Objects.requireNonNull(law, "law");
        Objects.requireNonNull(loosening, "loosening");
        if (rank < 1 || attemptsBroken < 0) {
            throw new IllegalArgumentException("RankingChoice: rank must be >= 1 and attemptsBroken >= 0, got rank " + rank + ", attemptsBroken " + attemptsBroken + " for " + law);
        }
    }
}
