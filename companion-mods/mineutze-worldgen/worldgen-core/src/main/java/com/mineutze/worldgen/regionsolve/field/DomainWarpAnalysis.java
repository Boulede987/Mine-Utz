package com.mineutze.worldgen.regionsolve.field;

/**
 * Checks whether a {@link DomainWarp} ever FOLDS space over a sampled
 * region — the actual mechanism behind the isolated-speck artifact seen at
 * high amplitude/frequency combinations in the field spike. Domain warping
 * bends the coordinate space before any distance comparison happens in it;
 * if the warp's own local rate of change gets steep enough, a small
 * unwarped neighborhood can turn inside-out in warped space, letting a
 * distant seed's territory reach into what should be an enclosed pocket of
 * another seed's — a real, provable failure of the warp function's local
 * injectivity, not "unlucky noise".
 *
 * <p>This is checked by the sign (and a safety margin below zero) of the
 * warp's local Jacobian determinant, via central finite differences — a
 * negative or near-zero determinant means the warp locally reverses or
 * collapses orientation right there, which is exactly a fold. Two uses,
 * same underlying math, both PRE-shape (never a repair pass over already-
 * generated output): {@link #minJacobianDeterminant}/{@link
 * #isFoldFreeWithMargin} run ONCE, offline, to choose fixed warp constants
 * provably fold-free everywhere; {@link #jacobianDeterminant} (per-point)
 * feeds {@link SafetyField}'s bounded per-region precompute, which locally
 * dampens amplitude only where a stronger flat constant would have folded,
 * decided before any biome shape for that region is generated (see doc/
 * DESIGN.md's "we don't compromise" / "we make it work" stance — a fold
 * prevented by construction needs no fallback).
 */
public final class DomainWarpAnalysis {

    private static final double FINITE_DIFFERENCE_STEP = 0.5;

    private DomainWarpAnalysis() {
    }

    /**
     * @param minSafeDeterminant a margin below the fold point (0), not just "not yet
     *                           folded" — 0 itself only rules out an actual fold already
     *                           having happened, with zero room for a slightly different
     *                           seed/region to tip it over. 0.15-0.3 (15-30% of the
     *                           unwarped area still preserved, worst case sampled) is a
     *                           reasonable margin to require.
     * @return the smallest Jacobian determinant found anywhere in the sampled square
     *         {@code [minCoord, maxCoord)} at {@code sampleStep} resolution — strictly
     *         above {@code minSafeDeterminant} means fold-free with that margin
     *         everywhere sampled; at or below means either an actual fold or too close
     *         to one to trust.
     */
    public static double minJacobianDeterminant(Warp2D warp, double minCoord, double maxCoord, double sampleStep) {
        double minDeterminant = Double.MAX_VALUE;
        for (double x = minCoord; x < maxCoord; x += sampleStep) {
            for (double z = minCoord; z < maxCoord; z += sampleStep) {
                minDeterminant = Math.min(minDeterminant, jacobianDeterminant(warp, x, z));
            }
        }
        return minDeterminant;
    }

    public static boolean isFoldFreeWithMargin(Warp2D warp, double minCoord, double maxCoord, double sampleStep, double minSafeDeterminant) {
        return minJacobianDeterminant(warp, minCoord, maxCoord, sampleStep) > minSafeDeterminant;
    }

    /** Exposed (not just the aggregate min) so {@link SafetyField} can build a per-point risk map from the SAME math this class's own aggregate check uses — one definition of "fold risk", not two that could drift apart. */
    public static double jacobianDeterminant(Warp2D warp, double x, double z) {
        double h = FINITE_DIFFERENCE_STEP;
        double[] plusX = warp.warp(x + h, z);
        double[] minusX = warp.warp(x - h, z);
        double[] plusZ = warp.warp(x, z + h);
        double[] minusZ = warp.warp(x, z - h);

        double dWarpedXdx = (plusX[0] - minusX[0]) / (2 * h);
        double dWarpedZdx = (plusX[1] - minusX[1]) / (2 * h);
        double dWarpedXdz = (plusZ[0] - minusZ[0]) / (2 * h);
        double dWarpedZdz = (plusZ[1] - minusZ[1]) / (2 * h);

        return dWarpedXdx * dWarpedZdz - dWarpedXdz * dWarpedZdx;
    }
}
