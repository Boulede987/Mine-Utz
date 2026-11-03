package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReliabilityVerdictTest {

    private static final double TARGET = 0.01;

    @Test
    void wholeRangeAtOrBelowTheTargetIsMet() {
        assertEquals(ReliabilityVerdict.MET, ReliabilityVerdict.of(new ReliabilityEstimate(0.0, TARGET, AttemptResult.Stage.COARSE), TARGET));
    }

    @Test
    void wholeRangeAboveTheTargetIsNotMet() {
        assertEquals(ReliabilityVerdict.NOT_MET, ReliabilityVerdict.of(new ReliabilityEstimate(0.02, 0.3, AttemptResult.Stage.COARSE), TARGET));
    }

    @Test
    void aRangeAcrossTheTargetIsUndecided() {
        assertEquals(ReliabilityVerdict.UNDECIDED, ReliabilityVerdict.of(new ReliabilityEstimate(0.0, 0.3, AttemptResult.Stage.COARSE), TARGET));
    }
}
