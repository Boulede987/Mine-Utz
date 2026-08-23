package com.mineutze.worldgen.regionsolve.field;

/**
 * Bends the coordinate space itself with two independent {@link CoherentNoise2D}
 * fields (one per axis) before any distance/field comparison happens against
 * it — the standard "domain warping" trick. Every candidate is compared in
 * the SAME warped space, so relative distances still mean something, but the
 * boundary between two candidates is only as straight as the underlying
 * noise field, never a raw straight line — arbitrarily curvy at arbitrarily
 * fine (raster) resolution, unlike jittering a handful of discrete polygon
 * vertices.
 */
public record DomainWarp(CoherentNoise2D noiseX, CoherentNoise2D noiseZ, double amplitude, double frequency, int octaves) implements Warp2D {

    public static final DomainWarp IDENTITY = new DomainWarp(new CoherentNoise2D(0), new CoherentNoise2D(0), 0, 1, 1);

    public double[] warp(double x, double z) {
        if (amplitude == 0) {
            return new double[]{x, z};
        }
        double warpedX = x + amplitude * noiseX.fbm(x * frequency, z * frequency, octaves);
        double warpedZ = z + amplitude * noiseZ.fbm(x * frequency, z * frequency, octaves);
        return new double[]{warpedX, warpedZ};
    }
}
