package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicTrigTest {

    @Test
    void sineAndCosineAreExactAtZero() {
        assertEquals(0.0, DeterministicTrig.sin(0.0), 0.0);
        assertEquals(1.0, DeterministicTrig.cos(0.0), 0.0);
    }

    /** The reference is StrictMath (fdlibm), which is itself reproducible; near the origin both are accurate to a couple of ulps. */
    @Test
    void agreesWithStrictMathOverAModerateRange() {
        Random random = new Random(1);
        double worstSine = 0;
        double worstCosine = 0;
        for (int sample = 0; sample < 200_000; sample++) {
            double x = (random.nextDouble() - 0.5) * 200;
            worstSine = Math.max(worstSine, Math.abs(DeterministicTrig.sin(x) - StrictMath.sin(x)));
            worstCosine = Math.max(worstCosine, Math.abs(DeterministicTrig.cos(x) - StrictMath.cos(x)));
        }
        assertEquals(0.0, worstSine, 3e-15);
        assertEquals(0.0, worstCosine, 3e-15);
    }

    /** The warp evaluates waves at arguments of a few thousand radians (wave number up to 0.16 per block times coordinates up to ~15,000 blocks). */
    @Test
    void staysAccurateForTheLargeArgumentsTheWarpUses() {
        Random random = new Random(2);
        double worst = 0;
        for (int sample = 0; sample < 100_000; sample++) {
            double x = (random.nextDouble() - 0.5) * 20_000;
            worst = Math.max(worst, Math.abs(DeterministicTrig.sin(x) - StrictMath.sin(x)));
            worst = Math.max(worst, Math.abs(DeterministicTrig.cos(x) - StrictMath.cos(x)));
        }
        assertEquals(0.0, worst, 5e-13);
    }

    @Test
    void sineSquaredPlusCosineSquaredIsOne() {
        Random random = new Random(3);
        for (int sample = 0; sample < 10_000; sample++) {
            double x = (random.nextDouble() - 0.5) * 5_000;
            double sine = DeterministicTrig.sin(x);
            double cosine = DeterministicTrig.cos(x);
            assertEquals(1.0, sine * sine + cosine * cosine, 2e-15, "at " + x);
        }
    }

    @Test
    void sineIsOddAndCosineIsEven() {
        Random random = new Random(4);
        for (int sample = 0; sample < 10_000; sample++) {
            double x = (random.nextDouble() - 0.5) * 3_000;
            assertEquals(-DeterministicTrig.sin(x), DeterministicTrig.sin(-x), 1e-15);
            assertEquals(DeterministicTrig.cos(x), DeterministicTrig.cos(-x), 1e-15);
        }
    }

    @Test
    void cosineIsSineShiftedByAQuarterTurn() {
        Random random = new Random(5);
        for (int sample = 0; sample < 10_000; sample++) {
            double x = (random.nextDouble() - 0.5) * 1_000;
            // x + pi/2 is itself rounded (half an ulp of x, up to 3e-14 for |x| near 500), so the two sides can differ by that much
            assertEquals(DeterministicTrig.sin(x + Math.PI / 2), DeterministicTrig.cos(x), 1e-13);
        }
    }

    @Test
    void everyQuadrantIsCoveredWithTheRightSign() {
        assertEquals(1.0, DeterministicTrig.sin(Math.PI / 2), 1e-15);
        assertEquals(0.0, DeterministicTrig.cos(Math.PI / 2), 1e-15);
        assertEquals(0.0, DeterministicTrig.sin(Math.PI), 1e-15);
        assertEquals(-1.0, DeterministicTrig.cos(Math.PI), 1e-15);
        assertEquals(-1.0, DeterministicTrig.sin(3 * Math.PI / 2), 1e-15);
        assertEquals(0.0, DeterministicTrig.cos(3 * Math.PI / 2), 1e-15);
        assertEquals(0.0, DeterministicTrig.sin(2 * Math.PI), 1e-15);
        assertEquals(1.0, DeterministicTrig.cos(2 * Math.PI), 1e-15);
    }

    @Test
    void anArgumentBeyondTheReductionRangeFailsInsteadOfReturningGarbage() {
        assertThrows(IllegalArgumentException.class, () -> DeterministicTrig.sin(1e9));
        assertThrows(IllegalArgumentException.class, () -> DeterministicTrig.cos(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> DeterministicTrig.sin(Double.POSITIVE_INFINITY));
    }
}
