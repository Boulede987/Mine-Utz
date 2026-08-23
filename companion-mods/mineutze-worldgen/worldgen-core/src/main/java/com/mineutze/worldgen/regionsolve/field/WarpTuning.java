package com.mineutze.worldgen.regionsolve.field;

/**
 * A named {@link DomainWarp} amplitude/frequency/octave combination, kept
 * separate from any specific {@link CoherentNoise2D} instance (those are
 * per-{@code worldSeed}, this is not).
 */
public record WarpTuning(double amplitude, double frequency, int octaves) {

    /**
     * A flat-safe warp (grid search over {@link DomainWarpAnalysis}, checked
     * at real region scale — a few hundred blocks was NOT enough: see the
     * class doc below) that stays above a 0.25 Jacobian-determinant
     * fold-safety margin everywhere sampled, across several worldSeeds — not
     * just "didn't visibly fold in one render". Two rejected earlier
     * candidates, both looked clean rendered but measured unsafe:
     * amplitude=40/frequency=0.01 (fold well within a 500x500 spike canvas)
     * and amplitude=90/frequency=0.004 (passed at spike scale, folded for
     * worldSeed 0 once checked across a real region-scale span — the reason
     * this tuning is now verified over [-2000, 2000], not [0, 500]).
     *
     * <p>frequency=0.008 (not the much lower 0.0015 tried first) is a
     * deliberate visual choice, not a safety one — a joint safety+coverage
     * search found the SAME ~1.2x-of-floor ceiling on {@link
     * AdaptiveWarpTuning}'s achievable gain at every frequency tried from
     * 0.0015 to 0.02, so frequency doesn't change how much amplitude
     * headroom adaptive damping buys. What it DOES change is how busy/
     * detailed the pattern looks: lower frequencies (fewer, broader waves)
     * read as smoother/"less weavy"; frequencies past ~0.01 actually looked
     * SMOOTHER again too, because the safe amplitude shrinks faster than
     * frequency rises (net wobble height keeps falling both directions).
     * 0.008 was the visual sweet spot found in that sweep. This amplitude
     * (25) is this frequency's OWN flat-safe ceiling, matching {@link
     * AdaptiveWarpTuning#RECOMMENDED_ADAPTIVE}'s floor. See {@code
     * DomainWarpAnalysisTest} for the assertion that keeps this specific
     * tuning proven fold-free going forward.
     */
    public static final WarpTuning RECOMMENDED_STRONG = new WarpTuning(25, 0.008, 3);
}
