package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RankingFingerprintTest {

    @Test
    void sameContentSameFingerprint() {
        assertEquals(RankingFingerprint.of("{\"ranking\": 1}", "graph 1"), RankingFingerprint.of("{\"ranking\": 1}", "graph 1"));
    }

    @Test
    void editedRankingChangesTheFingerprint() {
        assertNotEquals(RankingFingerprint.of("{\"ranking\": 1}", "graph 1"), RankingFingerprint.of("{\"ranking\": 2}", "graph 1"));
    }

    /** After a graph edit the same rank can mean another law (laws the edit changed drop out). */
    @Test
    void editedGraphChangesTheFingerprint() {
        assertNotEquals(RankingFingerprint.of("{\"ranking\": 1}", "graph 1"), RankingFingerprint.of("{\"ranking\": 1}", "graph 2"));
    }
}
