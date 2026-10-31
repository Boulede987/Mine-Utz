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
 * {@code failedCost} + {@code closestState}. Render: solved one carries the rendered pattern + {@code renderedMap},
 * failed one {@code renderFailureReason}.
 */
record AttemptResult(
    Stage stage, boolean solved, OptionalInt failedCost, Optional<Map<ToroidalPos, BiomeId>> solvedPattern, Optional<Map<ToroidalPos, BiomeId>> closestState,
    Optional<BiomeMap> renderedMap, Optional<String> renderFailureReason, Duration duration
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
        Objects.requireNonNull(renderedMap, "renderedMap");
        Objects.requireNonNull(renderFailureReason, "renderFailureReason");
        Objects.requireNonNull(duration, "duration");
        if (stage == Stage.REALIZATION) {
            requireRenderShape(solved, failedCost, solvedPattern, closestState, renderedMap, renderFailureReason);
        } else {
            requireSearchShape(stage, solved, failedCost, solvedPattern, closestState, renderedMap, renderFailureReason);
        }
    }

    static AttemptResult ofSearch(Stage stage, ToroidalSizedLocalSearch.Result result, Duration duration) {
        if (stage == Stage.REALIZATION) {
            throw new IllegalArgumentException("AttemptResult.ofSearch: render is not a search stage, use rendered/renderFailed");
        }
        boolean solved = result.assignment() != null;
        Optional<Map<ToroidalPos, BiomeId>> solvedPattern = solved ? Optional.of(Collections.unmodifiableMap(result.assignment())) : Optional.empty();
        Optional<Map<ToroidalPos, BiomeId>> closestState = solved ? Optional.empty() : Optional.of(Collections.unmodifiableMap(result.closestAssignment()));
        OptionalInt failedCost = solved ? OptionalInt.empty() : OptionalInt.of(result.bestCost());
        return new AttemptResult(stage, solved, failedCost, solvedPattern, closestState, Optional.empty(), Optional.empty(), duration);
    }

    static AttemptResult rendered(Map<ToroidalPos, BiomeId> pattern, BiomeMap map, Duration duration) {
        return new AttemptResult(
            Stage.REALIZATION, true, OptionalInt.empty(), Optional.of(Collections.unmodifiableMap(pattern)), Optional.empty(), Optional.of(map), Optional.empty(), duration
        );
    }

    static AttemptResult renderFailed(String reason, Duration duration) {
        return new AttemptResult(Stage.REALIZATION, false, OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason), duration);
    }

    private static void requireRenderShape(
        boolean solved, OptionalInt failedCost, Optional<Map<ToroidalPos, BiomeId>> solvedPattern, Optional<Map<ToroidalPos, BiomeId>> closestState,
        Optional<BiomeMap> renderedMap, Optional<String> renderFailureReason
    ) {
        boolean consistent = failedCost.isEmpty() && closestState.isEmpty() && solved == solvedPattern.isPresent() && solved == renderedMap.isPresent()
            && solved != renderFailureReason.isPresent();
        if (!consistent) {
            throw new IllegalArgumentException(
                "AttemptResult: render attempt carries pattern + map exactly when solved, a reason exactly when failed, no search cost; got solved=" + solved
                    + ", failedCost=" + failedCost + ", pattern present=" + solvedPattern.isPresent() + ", closest present=" + closestState.isPresent() + ", map present="
                    + renderedMap.isPresent() + ", reason present=" + renderFailureReason.isPresent()
            );
        }
    }

    private static void requireSearchShape(
        Stage stage, boolean solved, OptionalInt failedCost, Optional<Map<ToroidalPos, BiomeId>> solvedPattern, Optional<Map<ToroidalPos, BiomeId>> closestState,
        Optional<BiomeMap> renderedMap, Optional<String> renderFailureReason
    ) {
        boolean inconsistent = solved != solvedPattern.isPresent() || solved == failedCost.isPresent() || solved == closestState.isPresent() || renderedMap.isPresent()
            || renderFailureReason.isPresent();
        if (inconsistent) {
            throw new IllegalArgumentException(
                "AttemptResult: " + stage + " attempt has a pattern exactly when solved, a cost + closest state exactly when failed, no render data; got solved="
                    + solved + ", pattern present=" + solvedPattern.isPresent() + ", failedCost=" + failedCost + ", closest present=" + closestState.isPresent()
                    + ", map present=" + renderedMap.isPresent() + ", reason present=" + renderFailureReason.isPresent()
            );
        }
    }
}
