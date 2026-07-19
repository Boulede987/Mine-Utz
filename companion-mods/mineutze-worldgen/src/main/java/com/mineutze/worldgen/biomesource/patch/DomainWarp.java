package com.mineutze.worldgen.biomesource.patch;

/**
 * Smooth 2D noise offset used to bend biome-boundary lookups into organic wavy curves
 * instead of straight cell/tile-grid lines. Sum of several octaves of bilinearly-
 * interpolated value noise (built from {@link PatchSeedHasher}, no external noise
 * dependency) at different wavelengths -- a single octave has a real, observed failure
 * mode: a boundary that happens to run roughly parallel to that one field's "quiet"
 * direction can look almost unwobbled over a long stretch, purely by geometric
 * coincidence, even though the field varies plenty everywhere else. Summing multiple
 * octaves at different wavelengths (standard fractal/fBm noise construction) means no
 * single boundary orientation can align with all of them at once.
 *
 * The smoothness of each octave is still load-bearing, not cosmetic: nearby blocks must
 * get nearly-identical offsets, or the accept/reject decision in
 * {@link SurfacePatchGrid#resolve} would flip unpredictably block-to-block and produce
 * speckling instead of a wavy line. Smoothstep interpolation (not plain linear) avoids
 * visible facets exactly at each octave's own lattice boundaries.
 *
 * Deliberately only warps the FINAL query-time lookup (see SurfacePatchGrid) -- never
 * fed into germination, growth, size targets, adjacency scoring, or terrain-class
 * classification, all of which keep running on the clean, unwarped grid. This is what
 * keeps patch size/adjacency/terrain-match guarantees intact while the visual edge
 * wobbles.
 */
final class DomainWarp {

    // fBm-style construction: each successive octave halves its lattice spacing (finer
    // detail) and multiplies its amplitude by OCTAVE_PERSISTENCE (contributes less).
    // Total amplitude budget across all octaves is comparable to the original
    // single-octave design (~10 blocks), just spread across several wavelengths instead
    // of concentrated in one.
    private static final int OCTAVE_COUNT = 3;
    private static final int BASE_LATTICE_SPACING_BLOCKS = 32;
    private static final double BASE_OCTAVE_AMPLITUDE_BLOCKS = 6.0;
    private static final double OCTAVE_PERSISTENCE = 0.55;
    private static final int MINIMUM_LATTICE_SPACING_BLOCKS = 4;

    // Distinct hash namespaces (see PatchSeedHasher) per (axis, octave), and separate
    // again from PatchGrowthEngine's own namespace uses (0, 1, 2) -- decorrelates every
    // octave from every other octave and from the other axis, so the warp isn't just a
    // scaled copy of itself at each wavelength.
    private static final int WARP_X_HASH_NAMESPACE_BASE = 10;
    private static final int WARP_Z_HASH_NAMESPACE_BASE = 20;

    private DomainWarp() {
    }

    static int offsetXBlocks(long worldSeed, int blockX, int blockZ) {
        return (int) Math.round(fractalNoise(worldSeed, WARP_X_HASH_NAMESPACE_BASE, blockX, blockZ));
    }

    static int offsetZBlocks(long worldSeed, int blockX, int blockZ) {
        return (int) Math.round(fractalNoise(worldSeed, WARP_Z_HASH_NAMESPACE_BASE, blockX, blockZ));
    }

    private static double fractalNoise(long worldSeed, int hashNamespaceBase, int blockX, int blockZ) {
        double total = 0.0;
        double amplitude = BASE_OCTAVE_AMPLITUDE_BLOCKS;
        int latticeSpacing = BASE_LATTICE_SPACING_BLOCKS;
        for (int octave = 0; octave < OCTAVE_COUNT; octave++) {
            total += smoothValueNoise(worldSeed, hashNamespaceBase + octave, blockX, blockZ, latticeSpacing) * amplitude;
            amplitude *= OCTAVE_PERSISTENCE;
            latticeSpacing = Math.max(MINIMUM_LATTICE_SPACING_BLOCKS, latticeSpacing / 2);
        }
        return total;
    }

    /** Returns a value in [-1, 1], smoothly (C1-continuous) interpolated between
     * deterministic lattice-point values at the given spacing. */
    private static double smoothValueNoise(long worldSeed, int hashNamespace, int blockX, int blockZ, int latticeSpacing) {
        int latticeX0 = Math.floorDiv(blockX, latticeSpacing) * latticeSpacing;
        int latticeZ0 = Math.floorDiv(blockZ, latticeSpacing) * latticeSpacing;
        int latticeX1 = latticeX0 + latticeSpacing;
        int latticeZ1 = latticeZ0 + latticeSpacing;

        double v00 = latticePointValue(worldSeed, hashNamespace, latticeX0, latticeZ0);
        double v10 = latticePointValue(worldSeed, hashNamespace, latticeX1, latticeZ0);
        double v01 = latticePointValue(worldSeed, hashNamespace, latticeX0, latticeZ1);
        double v11 = latticePointValue(worldSeed, hashNamespace, latticeX1, latticeZ1);

        double tx = smoothstep((blockX - latticeX0) / (double) latticeSpacing);
        double tz = smoothstep((blockZ - latticeZ0) / (double) latticeSpacing);

        double top = lerp(v00, v10, tx);
        double bottom = lerp(v01, v11, tx);
        return lerp(top, bottom, tz);
    }

    /** Deterministic [-1, 1] value at one lattice point. */
    private static double latticePointValue(long worldSeed, int hashNamespace, int latticeX, int latticeZ) {
        long seed = PatchSeedHasher.cellSeed(worldSeed, latticeX, hashNamespace, latticeZ);
        return PatchSeedHasher.unitIntervalFromSeed(seed) * 2.0 - 1.0;
    }

    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
