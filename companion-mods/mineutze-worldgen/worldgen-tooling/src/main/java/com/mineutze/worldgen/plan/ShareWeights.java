package com.mineutze.worldgen.plan;

import java.util.List;
import java.util.Optional;

/**
 * Integer weights for "each patch meets one share": sum_i (multiple / min_i) * edges_i >= multiple * patches, multiple = least common multiple of the minimums. Empty when any
 * term could overflow int - Choco would then compute wrong bounds and could report a false CONTRADICTION; the caller
 * skips the constraint instead (dropping a rule keeps the model a relaxation, so proofs stay valid).
 */
record ShareWeights(List<Integer> shareWeights, int patchWeight) {

    ShareWeights {
        shareWeights = List.copyOf(shareWeights);
    }

    /**
     * @param minimums         each share's minimum edges, all >= 1
     * @param maxEdgesPerShare largest total of edges any one share's partners can reach
     * @param maxPatches       largest patch count of the requiring biome
     */
    static Optional<ShareWeights> of(List<Integer> minimums, long maxEdgesPerShare, long maxPatches) {
        long leastCommonMultiple = 1;
        for (int minimum : minimums) {
            if (minimum < 1) {
                throw new IllegalArgumentException("ShareWeights: share minimums must be >= 1, got " + minimums);
            }
            leastCommonMultiple = leastCommonMultiple / greatestCommonDivisor(leastCommonMultiple, minimum) * minimum;
            if (leastCommonMultiple > Integer.MAX_VALUE) {
                return Optional.empty();
            }
        }
        long multiple = leastCommonMultiple;
        List<Integer> weights = minimums.stream().map(minimum -> (int) (multiple / minimum)).toList();
        long largestTotal = multiple * maxPatches + weights.stream().mapToLong(weight -> weight * maxEdgesPerShare).sum();
        if (largestTotal > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        return Optional.of(new ShareWeights(weights, (int) multiple));
    }

    private static long greatestCommonDivisor(long first, long second) {
        long larger = first;
        long smaller = second;
        while (smaller != 0) {
            long remainder = larger % smaller;
            larger = smaller;
            smaller = remainder;
        }
        return larger;
    }
}
