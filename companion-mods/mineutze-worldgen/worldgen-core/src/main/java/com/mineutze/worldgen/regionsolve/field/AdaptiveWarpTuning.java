package com.mineutze.worldgen.regionsolve.field;

/**
 * Named parameter bundle for {@link AdaptiveDomainWarp} + the {@link
 * SafetyField} it's built from — separate from any specific {@link
 * CoherentNoise2D} pair, same reasoning as {@link WarpTuning}.
 */
public record AdaptiveWarpTuning(double floorAmplitude, double strongAmplitude, double frequency, int octaves, double latticeSpacing, double dangerDeterminant, double comfortableDeterminant, int dilateRadiusCells) {

    /**
     * {@code floorAmplitude} = {@link WarpTuning#RECOMMENDED_STRONG}'s own flat-safe
     * amplitude at this SAME frequency (0.008) — every pocket the safety field dampens
     * still gets that much wobble, never anywhere close to a straight edge.
     *
     * <p><b>strongAmplitude=30 is a genuine but modest gain over the floor (+20%), not a
     * dramatic multiplier</b> — worth recording why, since the mistake (claiming a huge
     * gain that was actually just "everywhere dampened to the floor") is easy to repeat.
     * A joint safety+coverage search (worst-point margin AND how much of the map stays
     * near full strength, not just the worst point alone) found this SAME ~1.2x-of-floor
     * ceiling at EVERY frequency tried, from 0.0015 up through 0.02 — it's a structural
     * property of how fast fold risk transitions from rare to pervasive once amplitude
     * exceeds the flat-safe floor, not something tied to this one frequency. A frequency
     * of 0.035 (the original "energetic" reference render this tuning was chased toward)
     * was measured to have 27% of its map ALREADY folded at amplitude 35 — not a rare
     * pocket any safety field can dampen without swallowing most of the map.
     *
     * <p>0.008 itself is a purely visual choice among frequencies that all share the same
     * ~20% ceiling: lower frequencies (0.0005-0.0015) read as smoother/broader waves;
     * frequencies past ~0.01 read as smoother again too (safe amplitude shrinks faster
     * than frequency rises, so net wobble height falls both directions) — 0.008 was the
     * visual sweet spot for texture/detail found in that sweep. {@code
     * dangerDeterminant=0.05}/{@code comfortableDeterminant=0.5} and {@code
     * dilateRadiusCells=2} (16 blocks) were chosen the same way the 0.0015 attempt's were:
     * requiring BOTH the aggregate worst-point margin AND real coverage, not just the
     * former alone. See {@code DomainWarpAnalysisTest} for the permanent fold-free and
     * coverage assertions.
     */
    public static final AdaptiveWarpTuning RECOMMENDED_ADAPTIVE = new AdaptiveWarpTuning(25, 30, 0.008, 3, 8, 0.05, 0.5, 2);
}
