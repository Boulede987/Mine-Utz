package com.mineutze.worldgen.plan;

import java.util.function.DoubleUnaryOperator;

/** Exact Clopper-Pearson one-sided bounds. Not normal approximation: verdicts live near rates 0 and 1, where it is worst. */
final class BinomialConfidence {

    private static final int BISECTION_STEPS = 100;

    private BinomialConfidence() {
    }

    /** Min rate with P(X >= successes) = 1 - confidence. 0 trials: 0 (no evidence = any rate). */
    static double lowerBound(int successes, int trials, double confidence) {
        requireValid(successes, trials, confidence);
        if (successes == 0) {
            return 0.0;
        }
        double alpha = 1.0 - confidence;
        return bisect(rate -> 1.0 - cumulativeProbability(successes - 1, trials, rate) - alpha);
    }

    /** Max rate with P(X <= successes) = 1 - confidence. 0 trials: 1 (no evidence = any rate). */
    static double upperBound(int successes, int trials, double confidence) {
        requireValid(successes, trials, confidence);
        if (successes == trials) {
            return 1.0;
        }
        double alpha = 1.0 - confidence;
        return bisect(rate -> alpha - cumulativeProbability(successes, trials, rate));
    }

    private static void requireValid(int successes, int trials, double confidence) {
        if (trials < 0 || successes < 0 || successes > trials) {
            throw new IllegalArgumentException("BinomialConfidence: need 0 <= successes <= trials, got " + successes + " of " + trials);
        }
        if (!(confidence > 0.0 && confidence < 1.0)) {
            throw new IllegalArgumentException("BinomialConfidence: confidence must be strictly between 0 and 1, got " + confidence);
        }
    }

    /** Root of an increasing function negative at 0, positive at 1. */
    private static double bisect(DoubleUnaryOperator increasing) {
        double atZero = increasing.applyAsDouble(0.0);
        double atOne = increasing.applyAsDouble(1.0);
        if (!(atZero < 0.0 && atOne > 0.0)) {
            throw new IllegalStateException("BinomialConfidence: no sign change on 0..1, f(0)=" + atZero + ", f(1)=" + atOne);
        }
        double low = 0.0;
        double high = 1.0;
        for (int step = 0; step < BISECTION_STEPS; step++) {
            double middle = (low + high) / 2.0;
            if (increasing.applyAsDouble(middle) < 0.0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (low + high) / 2.0;
    }

    /** P(X <= atMost) for X ~ Binomial(trials, rate). Log space: trial counts reach the thousands. */
    private static double cumulativeProbability(int atMost, int trials, double rate) {
        if (rate <= 0.0) {
            return 1.0;
        }
        if (rate >= 1.0) {
            return atMost >= trials ? 1.0 : 0.0;
        }
        double logRate = Math.log(rate);
        double logOneMinusRate = Math.log1p(-rate);
        double logBinomialCoefficient = 0.0;
        double sum = 0.0;
        for (int successes = 0; successes <= atMost; successes++) {
            if (successes > 0) {
                logBinomialCoefficient += Math.log(trials - successes + 1) - Math.log(successes);
            }
            sum += Math.exp(logBinomialCoefficient + successes * logRate + (trials - successes) * logOneMinusRate);
        }
        return Math.min(1.0, sum);
    }
}
