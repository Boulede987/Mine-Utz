package com.mineutze.worldgen.regionsolve.field;

/**
 * Same domain-warping idea as {@link DomainWarp}, but amplitude is a
 * per-point value instead of one flat constant: {@code floorAmplitude} +
 * {@code (strongAmplitude - floorAmplitude) * safetyField.safetyAt(x, z)}.
 * Reuses the SAME {@link CoherentNoise2D} pair a plain {@link DomainWarp}
 * would, so the wobble's underlying shape is identical — only its local
 * strength varies, ramping down smoothly (never abruptly — an abrupt
 * amplitude cliff would introduce its own fold via a steep amplitude
 * gradient term in the Jacobian) in exactly the pockets {@link SafetyField}
 * flagged as risky for the strong end.
 *
 * <p><b>Not safe by assumption</b> — the whole combined function (including
 * the amplitude modulation's own contribution to the local Jacobian) must
 * still be verified via {@link DomainWarpAnalysis} same as any other {@link
 * Warp2D}, exactly as {@code DomainWarpAnalysisTest} does for {@link
 * WarpTuning#RECOMMENDED_ADAPTIVE}. A too-coarse {@link SafetyField} lattice
 * or too-narrow a smoothstep band between danger/comfortable thresholds
 * would show up there as a real failure, not a silent gap.
 */
public record AdaptiveDomainWarp(CoherentNoise2D noiseX, CoherentNoise2D noiseZ, double floorAmplitude, double strongAmplitude, double frequency, int octaves, SafetyField safetyField) implements Warp2D {

    @Override
    public double[] warp(double x, double z) {
        double amplitude = floorAmplitude + (strongAmplitude - floorAmplitude) * safetyField.safetyAt(x, z);
        double warpedX = x + amplitude * noiseX.fbm(x * frequency, z * frequency, octaves);
        double warpedZ = z + amplitude * noiseZ.fbm(x * frequency, z * frequency, octaves);
        return new double[]{warpedX, warpedZ};
    }
}
