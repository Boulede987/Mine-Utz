package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeasibilityBudgetTest {

    @Test
    void editorDefaultIsTheValidatedTwoMinuteOnePercentAtNinetyNinePercent() {
        FeasibilityBudget budget = FeasibilityBudget.EDITOR_DEFAULT;

        assertEquals(Duration.ofMinutes(2), budget.wallTime());
        assertEquals(0.01, budget.maxWorldFailureProbability());
        assertEquals(0.99, budget.confidence());
        assertEquals(Runtime.getRuntime().availableProcessors(), budget.threads());
    }

    @Test
    void rejectsANonPositiveWallTime() {
        assertThrows(IllegalArgumentException.class, () -> new FeasibilityBudget(Duration.ZERO, 4, 0.01, 0.99));
    }

    @Test
    void rejectsNoThreads() {
        assertThrows(IllegalArgumentException.class, () -> new FeasibilityBudget(Duration.ofMinutes(2), 0, 0.01, 0.99));
    }

    @Test
    void rejectsATargetOutsideTheOpenUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new FeasibilityBudget(Duration.ofMinutes(2), 4, 0.0, 0.99));
    }

    @Test
    void rejectsAConfidenceOutsideTheOpenUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> new FeasibilityBudget(Duration.ofMinutes(2), 4, 0.01, 1.0));
    }
}
