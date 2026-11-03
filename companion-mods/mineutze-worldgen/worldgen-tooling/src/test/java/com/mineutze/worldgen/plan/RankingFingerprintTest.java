package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RankingFingerprintTest {

    @Test
    void sameContentSameFingerprint() {
        assertEquals(RankingFingerprint.of("{\"ranking\": 1}"), RankingFingerprint.of("{\"ranking\": 1}"));
    }

    @Test
    void editedRankingChangesTheFingerprint() {
        assertNotEquals(RankingFingerprint.of("{\"ranking\": 1}"), RankingFingerprint.of("{\"ranking\": 2}"));
    }
}
