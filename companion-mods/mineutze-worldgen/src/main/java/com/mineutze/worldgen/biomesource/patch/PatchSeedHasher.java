package com.mineutze.worldgen.biomesource.patch;

/**
 * Deterministic per-cell seed derivation for the patch-growth engine
 * ({@link PatchGrowthEngine}). Must stay bit-identical to Python's
 * pythonScripts/worldgen/patch_seed_hash.py so the Streamlit preview and the real
 * in-game BiomeSource derive the same germination choices, spacing, and tie-break
 * priorities from the same (world seed, coords).
 *
 * Uses a splitmix64-style bit mix rather than seeding a general-purpose PRNG per cell:
 * per-cell PRNGs seeded from small, nearby integer coordinates have poor avalanche
 * behaviour -- neighbouring cells can produce correlated first outputs. splitmix64's
 * finalizer round is a standard, well-mixed alternative for exactly this "hash a
 * handful of small integers into an unrelated-looking 64-bit value" use case.
 */
public final class PatchSeedHasher {

    // Constants from the reference splitmix64 finalizer (Vigna & Steele) -- not
    // modpack-specific, do not rename to something more "descriptive": they are the
    // algorithm.
    private static final long SPLITMIX64_INCREMENT    = 0x9E3779B97F4A7C15L;
    private static final long SPLITMIX64_MULTIPLIER_1 = 0xBF58476D1CE4E5B9L;
    private static final long SPLITMIX64_MULTIPLIER_2 = 0x94D049BB133111EBL;

    private PatchSeedHasher() {
    }

    /** One splitmix64 finalizer round: hashes seed into a well-avalanched 64-bit value. */
    public static long mix64Bits(long seed) {
        seed += SPLITMIX64_INCREMENT;
        long mixed = seed;
        mixed = (mixed ^ (mixed >>> 30)) * SPLITMIX64_MULTIPLIER_1;
        mixed = (mixed ^ (mixed >>> 27)) * SPLITMIX64_MULTIPLIER_2;
        return mixed ^ (mixed >>> 31);
    }

    /** Deterministic per-cell seed. cellY is 0 for the 2D surface tier. */
    public static long cellSeed(long worldSeed, int cellX, int cellY, int cellZ) {
        long mixed = worldSeed;
        mixed = mix64Bits(mixed ^ cellX);
        mixed = mix64Bits(mixed ^ cellY);
        mixed = mix64Bits(mixed ^ cellZ);
        return mixed;
    }

    /**
     * [0, 1) double derived from a seed -- used for germination rolls, patch-size
     * sampling, and tie-break priorities.
     */
    public static double unitIntervalFromSeed(long seed) {
        long top32Bits = seed & 0xFFFFFFFFL;
        return top32Bits / (double) (1L << 32);
    }
}
