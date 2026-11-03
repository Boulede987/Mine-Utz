package com.mineutze.worldgen.plan;

import java.util.Objects;

/**
 * A ranking as read from its file against the current graph, with the fingerprint of those bytes and that graph. Read on
 * an edited graph: counts are from the previous version, laws the edit changed are removed (removedByEdits).
 */
record SavedRanking(TightnessRanking ranking, String fingerprint, boolean measuredOnThisGraph, int removedByEdits) {

    SavedRanking {
        Objects.requireNonNull(ranking, "ranking");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (removedByEdits < 0 || (measuredOnThisGraph && removedByEdits > 0)) {
            throw new IllegalArgumentException("SavedRanking: removedByEdits must be >= 0, and 0 on the graph it was measured on; got " + removedByEdits
                + ", measuredOnThisGraph " + measuredOnThisGraph);
        }
    }

    /** @throws IllegalArgumentException picks were made on another ranking (a newer Solve check replaced it) */
    void requireFingerprint(String pickedOn) {
        if (!fingerprint.equals(pickedOn)) {
            throw new IllegalArgumentException("The ranking changed since the page showed it (a newer Solve check replaced it, or the graph was edited): reload the ranking and pick again.");
        }
    }
}
