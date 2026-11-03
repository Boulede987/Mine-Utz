package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RankingViewTest {

    private static RankingChoice choice(int rank) {
        return new RankingChoice(rank, "law " + rank, 1, Optional.empty());
    }

    @Test
    void refusesRanksOutOfSequence() {
        assertThrows(IllegalArgumentException.class, () -> new RankingView(List.of(choice(1), choice(3)), 5, "abc", true, 0));
    }

    @Test
    void refusesABlankFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new RankingView(List.of(choice(1)), 5, " ", true, 0));
    }

    @Test
    void refusesNegativeFailedAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new RankingView(List.of(choice(1)), -1, "abc", true, 0));
    }

    @Test
    void aChoiceRefusesARankBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> choice(0));
    }

    @Test
    void aChoiceRefusesNegativeAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new RankingChoice(1, "law", -1, Optional.empty()));
    }
}
