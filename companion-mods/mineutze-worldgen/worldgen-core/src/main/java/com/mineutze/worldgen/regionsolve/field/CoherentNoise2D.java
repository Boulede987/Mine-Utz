package com.mineutze.worldgen.regionsolve.field;

/**
 * Deterministic, continuous 2D value noise: {@link #sample} is smooth
 * (bilinear-interpolated between hashed integer-lattice values, cubic
 * smoothstep easing) so nearby points get correlated values — the property
 * both deleted prior attempts lacked (they jittered discrete polygon
 * vertices independently, so adjacent boundary points had uncorrelated
 * offsets and no amount of amplitude tuning produced a real curve). {@link
 * #fbm} layers several octaves for richer, less grid-aligned detail.
 *
 * <p>No persisted {@link java.util.Random} stream — every lattice value is
 * a pure hash of {@code (seed, latticeX, latticeZ)}, same idiom the rest of
 * this codebase uses for deterministic-per-input noise.
 */
public final class CoherentNoise2D {

    private final long seed;

    public CoherentNoise2D(long seed) {
        this.seed = seed;
    }

    /** Continuous, range [-1, 1]. */
    public double sample(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = smoothstep(x - x0);
        double fz = smoothstep(z - z0);

        double n00 = latticeValue(x0, z0);
        double n10 = latticeValue(x0 + 1, z0);
        double n01 = latticeValue(x0, z0 + 1);
        double n11 = latticeValue(x0 + 1, z0 + 1);

        double ix0 = lerp(n00, n10, fx);
        double ix1 = lerp(n01, n11, fx);
        return lerp(ix0, ix1, fz);
    }

    /** Sum of {@code octaves} layers of {@link #sample} at doubling frequency and halving amplitude, normalized back to [-1, 1]. */
    public double fbm(double x, double z, int octaves) {
        double sum = 0;
        double amplitude = 1;
        double frequency = 1;
        double amplitudeSum = 0;
        for (int octave = 0; octave < octaves; octave++) {
            sum += amplitude * sample(x * frequency, z * frequency);
            amplitudeSum += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return sum / amplitudeSum;
    }

    private double latticeValue(int latticeX, int latticeZ) {
        long hash = seed;
        hash = hash * 31 + latticeX;
        hash = hash * 31 + latticeZ;
        hash ^= (hash >>> 33);
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= (hash >>> 33);
        hash *= 0xC4CEB9FE1A85EC53L;
        hash ^= (hash >>> 33);
        return ((hash >>> 40) & 0xFFFFFF) / (double) 0xFFFFFF * 2.0 - 1.0;
    }

    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
