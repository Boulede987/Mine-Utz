package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One finished attempt of one stage. Search stages: solved iff {@code solvedPattern} present, failed one carries
 * {@code failedCost} + {@code closestState}. Render: no search state, failed one carries {@code renderFailureReason}.
 */
record AttemptResult(
    Stage stage, boolean solved, OptionalInt failedCost, Optional<Map<ToroidalPos, BiomeId>> solvedPattern, Optional<Map<ToroidalPos, BiomeId>> closestState,
    Optional<String> renderFailureReason, Duration duration
) {

    enum Stage {
        COARSE,
        REFINEMENT,
        REALIZATION
    }

    AttemptResult {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(failedCost, "failedCost");
        Objects.requireNonNull(solvedPattern, "solvedPattern");
        Objects.requireNonNull(closestState, "closestState");
        Objects.requireNonNull(renderFailureReason, "renderFailureReason");
        Objects.requireNonNull(duration, "duration");
        if (stage == Stage.REALIZATION) {
            requireRenderShape(solved, failedCost, solvedPattern, closestState, renderFailureReason);
        } else {
            requireSearchShape(stage, solved, failedCost, solvedPattern, renderFailureReason);
        }
    }

    static AttemptResult ofSearch(Stage stage, ToroidalSizedLocalSearch.Result result, Duration duration) {
        boolean solved = result.assignment() != null;
        Optional<Map<ToroidalPos, BiomeId>> solvedPattern = solved ? Optional.of(Collections.unmodifiableMap(result.assignment())) : Optional.empty();
        Optional<Map<ToroidalPos, BiomeId>> closestState = solved ? Optional.empty() : Optional.of(Collections.unmodifiableMap(result.closestAssignment()));
        OptionalInt failedCost = solved ? OptionalInt.empty() : OptionalInt.of(result.bestCost());
        return new AttemptResult(stage, solved, failedCost, solvedPattern, closestState, Optional.empty(), duration);
    }

    static AttemptResult rendered(Duration duration) {
        return new AttemptResult(Stage.REALIZATION, true, OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), duration);
    }

    static AttemptResult renderFailed(String reason, Duration duration) {
        return new AttemptResult(Stage.REALIZATION, false, OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.of(reason), duration);
    }

    private static void requireRenderShape(
        boolean solved, OptionalInt failedCost, Optional<Map<ToroidalPos, BiomeId>> solvedPattern, Optional<Map<ToroidalPos, BiomeId>> closestState,
        Optional<String> renderFailureReason
    ) {
        if (failedCost.isPresent() || solvedPattern.isPresent() || closestState.isPresent() || solved == renderFailureReason.isPresent()) {
            throw new IllegalArgumentException(
                "AttemptResult: render attempt carries no search state, and a reason exactly when it failed; got solved=" + solved + ", failedCost=" + failedCost
                    + ", pattern present=" + solvedPattern.isPresent() + ", closest present=" + closestState.isPresent() + ", reason present=" + renderFailureReason.isPresent()
            );
        }
    }

    private static void requireSearchShape(
        Stage stage, boolean solved, OptionalInt failedCost, Optional<Map<ToroidalPos, BiomeId>> solvedPattern, Optional<String> renderFailureReason
    ) {
        if (solved != solvedPattern.isPresent() || solved == failedCost.isPresent() || renderFailureReason.isPresent()) {
            throw new IllegalArgumentException(
                "AttemptResult: " + stage + " attempt has a pattern exactly when solved and a cost exactly when failed; got solved=" + solved + ", pattern present="
                    + solvedPattern.isPresent() + ", failedCost=" + failedCost + ", reason present=" + renderFailureReason.isPresent()
            );
        }
    }
}
