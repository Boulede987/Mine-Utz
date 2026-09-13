package com.mineutze.worldgen.regionsolve;

import java.util.ArrayList;
import java.util.List;

/**
 * Macro-cell/solve-region grid the constraint solver operates on. 4-block cells match
 * vanilla's biome resolution (Minecraft samples one biome per 4x4x4-block quart volume -
 * {@code getNoiseBiome} already gets quart coords) - 2026-08-15, replacing earlier
 * 64-block (4x4 chunk) placeholder. {@code regionSizeCellsPerSide} scaled up 16x with it
 * (32 → 512) to keep the same 2048-block region footprint: max-size biome patch (desert,
 * 1000 blocks) needs room relative to region regardless of cell grid fineness - always
 * about block width not cell count, so unchanged.
 */
public record MacroGridConfig(int macroCellSizeBlocks, int regionSizeCellsPerSide) {

    public static final MacroGridConfig DEFAULT = new MacroGridConfig(4, 512);

    // Region-grid domain warp: straight region-solve boundary reads as hard ruler-straight
    // cut wherever a patch reaches it, no patch-level fix hides it (3 pixel-cleanup passes
    // tried - PinchResolver, DiagonalCornerResolver, SeparatorWidthEnforcer, deleted
    // 2026-08-15 once this warp made them unneeded, see git history - none worked, since
    // STRAIGHT LINE is the region partition itself, not a patch shape). Real fix: warp the
    // boundary instead of forcing it straight - it was never a real geometric feature worth
    // straightness.
    //
    // AMPLITUDE_CELLS must stay well under half regionSizeCellsPerSide (32) - rest of
    // codebase (RegionBoundaryCollector.neighborsOf, SeedRegionCache's concurrent-race
    // locking) assumes a region's true geometric neighbors are only its immediate 8
    // (Moore), never 2 regions away. Capped at region/4 (8 cells for DEFAULT), well under
    // that region/2 hard ceiling even with both axes' warps compounding at a corner.
    //
    // AMPLITUDE and WAVELENGTH are coupled, not independent - max per-cell slope is
    // AMPLITUDE*(2*PI/WAVELENGTH). Real ceiling is region/2 bound above, not any slope
    // value: single sine wave always too regular (exactly one bump, no real coastline
    // looks like that), so sums WARP_OCTAVE_COUNT independently-phased sine layers instead
    // (fractional-Brownian-motion construction, same idea Perlin/simplex noise uses) - each
    // shorter-wavelength/smaller-amplitude than last, own phase so layers don't all cross
    // zero at same spot and cancel into a disguised single bump. Octave amplitudes
    // normalized (divided by geometric series sum in octaveAmplitudeNormalizer) so TOTAL
    // displacement across octaves still respects WARP_AMPLITUDE_CELLS_CAP exactly
    // regardless of wavelength/persistence tuning.
    //
    // WARP_WAVELENGTH_CELLS deliberately comparable to (slightly under) region side
    // itself, not a larger "safe-looking" number: dominant octave must complete full cycle
    // - real direction reversal, not just a bow - within one border's length, else curve
    // still reads as one monotonic bend regardless of higher-octave texture.
    // WARP_PERSISTENCE (0.55) high enough next 1-2 octaves carry comparable weight instead
    // of fading - real irregularity needs several competing scales, not one dominant curve
    // plus noise. Stricter "keep slope well under 1" rule considered, rejected:
    // cellsInRegion's brute-force scan + regionOf's per-cell test give every cell exactly
    // one region unconditionally regardless of warpOffset's slope, so rougher curve carries
    // no gap/overlap risk - only hard invariant is total amplitude bound (region/2 above),
    // which normalizer still guarantees exactly. Verified empirically - see
    // MacroGridConfigTest.warpNeverBleedsPastTheImmediateNeighborRegion, sweeps real cells,
    // checks amplitude bound directly regardless of curve roughness.
    private static final double WARP_AMPLITUDE_CELLS_CAP = 8.0;
    private static final double WARP_AMPLITUDE_FRACTION = 0.25;
    private static final double WARP_WAVELENGTH_CELLS = 28.0;
    private static final int WARP_OCTAVE_COUNT = 4;
    private static final double WARP_LACUNARITY = 2.2;
    private static final double WARP_PERSISTENCE = 0.55;
    private static final double WARP_OCTAVE_AMPLITUDE_NORMALIZER = octaveAmplitudeNormalizer();
    private static final long WARP_SALT_X = 0x9E37_79B9_7F4A_7C15L;
    private static final long WARP_SALT_Z = 0xC2B2_AE3D_27D4_EB4FL;
    // At/below this, regionSizeCellsPerSide so small (only synthetic test grids -
    // MacroGridConfig.DEFAULT is 32, 8 a common small-fixture size in tests) EVERY cell
    // boundary is already a region boundary; any nonzero warp could flip a cell into a
    // neighbor region with no stable "interior" cells left - warp amplitude 0 at/below
    // this threshold instead of chasing an ever-smaller fraction. Inclusive (<=, not <) on
    // purpose - confirmed via real test breakage: a size-8 grid exactly AT threshold still
    // picked up real displacement once WARP_WAVELENGTH_CELLS shrank, flipping cell counts
    // several small-fixture tests asserted exactly.
    private static final int WARP_MIN_REGION_SIZE = 8;

    public MacroGridConfig {
        if (macroCellSizeBlocks <= 0) {
            throw new IllegalArgumentException("macroCellSizeBlocks must be positive, got " + macroCellSizeBlocks);
        }
        if (regionSizeCellsPerSide <= 0) {
            throw new IllegalArgumentException("regionSizeCellsPerSide must be positive, got " + regionSizeCellsPerSide);
        }
    }

    /**
     * Vertical (constant-nominal-x) boundaries wave as smooth function of z, horizontal
     * (constant-nominal-z) wave as smooth function of x - each axis's warp depends only on
     * the OTHER axis's coord, so along any fixed row/column the mapping is a
     * constant-shifted floor-division (still monotonic, gap-free); combined 2D partition
     * stays simply-connected, gently-bowed blobs instead of a rigid grid.
     */
    public RegionPos regionOf(MacroCellPos cell, long worldSeed) {
        double warpedX = cell.x() + warpOffset(cell.z(), worldSeed, WARP_SALT_X);
        double warpedZ = cell.z() + warpOffset(cell.x(), worldSeed, WARP_SALT_Z);
        return new RegionPos(
            Math.floorDiv((int) Math.floor(warpedX), regionSizeCellsPerSide),
            Math.floorDiv((int) Math.floor(warpedZ), regionSizeCellsPerSide)
        );
    }

    /** Caps at fixed amplitude for real-sized grid ({@code DEFAULT} included), scales down for small synthetic test grid instead of overwhelming it. */
    private double warpAmplitudeCells() {
        if (regionSizeCellsPerSide <= WARP_MIN_REGION_SIZE) {
            return 0.0;
        }
        return Math.min(WARP_AMPLITUDE_CELLS_CAP, regionSizeCellsPerSide * WARP_AMPLITUDE_FRACTION);
    }

    /** Sum of {@link #WARP_OCTAVE_COUNT} independently-phased sine layers, shrinking wavelength and amplitude together each octave — see this class's own field-level doc for why a single sine can never look natural regardless of tuning. */
    private double warpOffset(int alongAxisCoord, long worldSeed, long axisSalt) {
        double totalAmplitude = warpAmplitudeCells();
        double wavelength = WARP_WAVELENGTH_CELLS;
        double offset = 0.0;
        for (int octave = 0; octave < WARP_OCTAVE_COUNT; octave++) {
            double octaveAmplitude = totalAmplitude * Math.pow(WARP_PERSISTENCE, octave) / WARP_OCTAVE_AMPLITUDE_NORMALIZER;
            double phase = octavePhase(worldSeed, axisSalt, octave);
            offset += octaveAmplitude * Math.sin(alongAxisCoord * (2.0 * Math.PI / wavelength) + phase);
            wavelength /= WARP_LACUNARITY;
        }
        return offset;
    }

    /** Independent per-octave phase (not just per-axis) - without it every octave crosses zero at the same along-axis coord, re-composing into a single disguised bump instead of real fractal detail. */
    private static double octavePhase(long worldSeed, long axisSalt, int octave) {
        long seed = worldSeed ^ axisSalt;
        seed = seed * 31 + octave;
        return ((seed >>> 32) ^ seed) % 1000 / 1000.0 * 2.0 * Math.PI;
    }

    /** Geometric series sum of {@code WARP_PERSISTENCE^octave} over {@link #WARP_OCTAVE_COUNT} octaves - dividing each octave's raw amplitude by this makes amplitudes sum back to exactly {@link #warpAmplitudeCells()}, so fractal detail never grows total displacement past what {@code WARP_AMPLITUDE_CELLS_CAP} already guarantees region/2-safe. */
    private static double octaveAmplitudeNormalizer() {
        double sum = 0.0;
        double amplitude = 1.0;
        for (int octave = 0; octave < WARP_OCTAVE_COUNT; octave++) {
            sum += amplitude;
            amplitude *= WARP_PERSISTENCE;
        }
        return sum;
    }

    /** Upper bound on how far {@link #regionOf}/{@link #cellsInRegion} can ever displace a cell from its nominal square for THIS grid - the margin {@link #cellsInRegion} pads its scan by. */
    public int warpMarginCells() {
        return (int) Math.ceil(warpAmplitudeCells()) + 1;
    }

    /**
     * Scans a bounding box padded by {@link #warpMarginCells()} on every side (not the
     * nominal square directly), keeps candidates that actually warp into this region -
     * only way to enumerate a warped region's true membership since the warp isn't
     * invertible in closed form. Cheap: at {@code regionSizeCellsPerSide=32}, ~1600
     * candidates tested vs 1024, not a real cost.
     */
    public List<MacroCellPos> cellsInRegion(RegionPos region, long worldSeed) {
        int margin = warpMarginCells();
        int minX = region.x() * regionSizeCellsPerSide - margin;
        int minZ = region.z() * regionSizeCellsPerSide - margin;
        int maxX = region.x() * regionSizeCellsPerSide + regionSizeCellsPerSide - 1 + margin;
        int maxZ = region.z() * regionSizeCellsPerSide + regionSizeCellsPerSide - 1 + margin;

        List<MacroCellPos> cells = new ArrayList<>(regionSizeCellsPerSide * regionSizeCellsPerSide);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                MacroCellPos candidate = new MacroCellPos(x, z);
                if (regionOf(candidate, worldSeed).equals(region)) {
                    cells.add(candidate);
                }
            }
        }
        return cells;
    }

    /** Approximates a biome's target width as a square patch of macro-cells. */
    public int cellCountForWidthBlocks(int widthBlocks) {
        double sideInCells = (double) widthBlocks / macroCellSizeBlocks;
        return Math.max(1, (int) Math.round(sideInCells * sideInCells));
    }

    /** Macro cell containing a continuous block-space point - one authoritative home for this floorDiv-by-cell-size math, shared by every caller going from a real coordinate (triangle centroid, query point) to its cell. */
    public MacroCellPos cellContaining(double blockX, double blockZ) {
        return new MacroCellPos(
            Math.floorDiv((int) Math.floor(blockX), macroCellSizeBlocks),
            Math.floorDiv((int) Math.floor(blockZ), macroCellSizeBlocks)
        );
    }

}
