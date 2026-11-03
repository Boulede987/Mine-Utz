package com.mineutze.worldgen.plan;

import java.util.List;

/** The saved ranking as the editor shows it; fingerprint ties picks to this very ranking. */
public record RankingView(List<RankingChoice> choices, int failedAttempts, String fingerprint, boolean measuredOnThisGraph, int removedByEdits) {

    /** Ranks must run 1, 2, 3...: a pick's rank indexes the saved ranking. */
    public RankingView {
        choices = List.copyOf(choices);
        if (fingerprint == null || fingerprint.isBlank() || failedAttempts < 0 || removedByEdits < 0) {
            throw new IllegalArgumentException("RankingView: needs a fingerprint, failedAttempts >= 0 and removedByEdits >= 0, got '" + fingerprint + "', "
                + failedAttempts + ", " + removedByEdits);
        }
        for (int index = 0; index < choices.size(); index++) {
            requireRank(choices.get(index), index + 1);
        }
    }

    private static void requireRank(RankingChoice choice, int expectedRank) {
        if (choice.rank() != expectedRank) {
            throw new IllegalArgumentException("RankingView: rank " + choice.rank() + " where " + expectedRank + " was expected (" + choice.law() + ")");
        }
    }
}
