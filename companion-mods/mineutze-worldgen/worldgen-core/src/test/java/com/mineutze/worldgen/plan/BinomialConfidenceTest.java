package com.mineutze.worldgen.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinomialConfidenceTest {

    private static final double TOLERANCE = 1e-4;

    @Test
    void lowerBoundIsZeroWhenNothingSucceeded() {
        assertEquals(0.0, BinomialConfidence.lowerBound(0, 50, 0.99));
    }

    @Test
    void upperBoundIsOneWhenEverythingSucceeded() {
        assertEquals(1.0, BinomialConfidence.upperBound(50, 50, 0.99));
    }

    /** Closed form of the exact (Clopper-Pearson) one-sided bound when every trial failed: 1 - alpha^(1/n). */
    @Test
    void upperBoundWithNoSuccessMatchesTheClosedForm() {
        double upper = BinomialConfidence.upperBound(0, 400, 0.99);

        assertEquals(1 - Math.pow(0.01, 1.0 / 400), upper, TOLERANCE);
    }

    /** Closed form when every trial succeeded: alpha^(1/n). */
    @Test
    void lowerBoundWithEverySuccessMatchesTheClosedForm() {
        double lower = BinomialConfidence.lowerBound(10, 10, 0.99);

        assertEquals(Math.pow(0.01, 1.0 / 10), lower, TOLERANCE);
    }

    /** Published Clopper-Pearson 90% two-sided interval for 5 of 10 is (0.2224, 0.7776), i.e. the 95% one-sided bounds. */
    @Test
    void interiorBoundsMatchPublishedClopperPearsonValues() {
        assertEquals(0.2224, BinomialConfidence.lowerBound(5, 10, 0.95), TOLERANCE);
        assertEquals(0.7776, BinomialConfidence.upperBound(5, 10, 0.95), TOLERANCE);
    }

    @Test
    void boundsAreUnknownWithoutTrials() {
        assertEquals(0.0, BinomialConfidence.lowerBound(0, 0, 0.99));
        assertEquals(1.0, BinomialConfidence.upperBound(0, 0, 0.99));
    }

    @Test
    void moreTrialsAtTheSameRateNarrowTheBounds() {
        double fewTrialsWidth = BinomialConfidence.upperBound(2, 10, 0.99) - BinomialConfidence.lowerBound(2, 10, 0.99);
        double manyTrialsWidth = BinomialConfidence.upperBound(20, 100, 0.99) - BinomialConfidence.lowerBound(20, 100, 0.99);

        assertTrue(manyTrialsWidth < fewTrialsWidth, "few: " + fewTrialsWidth + ", many: " + manyTrialsWidth);
    }

    @Test
    void rejectsMoreSuccessesThanTrials() {
        assertThrows(IllegalArgumentException.class, () -> BinomialConfidence.lowerBound(11, 10, 0.99));
    }

    @Test
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> BinomialConfidence.upperBound(-1, 10, 0.99));
    }

    @Test
    void rejectsConfidenceOutsideTheOpenUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> BinomialConfidence.lowerBound(1, 10, 1.0));
        assertThrows(IllegalArgumentException.class, () -> BinomialConfidence.upperBound(1, 10, 0.0));
    }
}
