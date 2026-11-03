package com.mineutze.worldgen.plan;

import java.util.Objects;

/** A ranking as read from its file, with the fingerprint of those very bytes. */
record SavedRanking(TightnessRanking ranking, String fingerprint) {

    SavedRanking {
        Objects.requireNonNull(ranking, "ranking");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /** @throws IllegalArgumentException picks were made on another ranking (a newer Solve check replaced it) */
    void requireFingerprint(String pickedOn) {
        if (!fingerprint.equals(pickedOn)) {
            throw new IllegalArgumentException("The ranking changed since the page showed it (a newer Solve check replaced it): reload the ranking and pick again.");
        }
    }
}
