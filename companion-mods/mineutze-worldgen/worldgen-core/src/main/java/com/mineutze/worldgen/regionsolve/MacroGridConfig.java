package com.mineutze.worldgen.regionsolve;

import java.util.ArrayList;
import java.util.List;

/**
 * The macro-cell/solve-region grid the constraint solver operates on.
 * 4-block cells match vanilla's own biome resolution (Minecraft samples one
 * biome per 4x4x4-block quart volume — {@code getNoiseBiome} already
 * receives quart coordinates) — 2026-08-15, replacing the earlier 64-block
 * (4x4 chunk) placeholder. {@code regionSizeCellsPerSide} scaled up 16x
 * alongside it (32 → 512) to hold the real-world region footprint at the
 * same 2048 blocks: a single max-size biome patch (desert's 1000 block
 * width) needs real room to breathe relative to the region regardless of
 * how fine the cell grid measuring it is — that reasoning was always about
 * block width, not cell count, so it transfers unchanged.
 */
public record MacroGridConfig(int macroCellSizeBlocks, int regionSizeCellsPerSide) {

    public static final MacroGridConfig DEFAULT = new MacroGridConfig(4, 512);

    // Region-grid domain warp: straight region-solve
    // boundaries read as a hard ruler-straight cut wherever a patch happens to reach one,
    // no amount of patch-level organicizing can hide it (three different pixel-level
    // cleanup passes tried and measured - PinchResolver, DiagonalCornerResolver,
    // SeparatorWidthEnforcer, all deleted 2026-08-15 once this warp made them
    // unnecessary - see this repo's own git history - none worked, because the STRAIGHT
    // LINE is the region partition itself, not a patch shape). Real fix: the region
    // boundary was never a real geometric feature worth being straight in the first
    // place - warp it instead of forcing it straight.
    //
    // AMPLITUDE_CELLS must stay well under half of regionSizeCellsPerSide (32) - the whole
    // rest of this codebase (RegionBoundaryCollector.neighborsOf, SeedRegionCache's
    // concurrent-race locking) assumes a region's true geometric neighbors are only its
    // immediate 8 (Moore) neighbors, never 2 regions away. Capped at region/4 (8 cells for
    // DEFAULT), comfortably under that region/2 hard ceiling even with two axes' warps
    // compounding at a corner.
    //
    // AMPLITUDE and WAVELENGTH are coupled, not independent dials - the max per-cell slope
    // is AMPLITUDE*(2*PI/WAVELENGTH). Real ceiling is the region/2 bound above, not any
    // particular slope value: a single sine wave is provably too regular no matter how
    // it's tuned (always exactly one bump, nothing a real coastline ever looks like), so
    // this sums WARP_OCTAVE_COUNT independently-phased sine layers instead (classic
    // fractional-Brownian-motion construction, the same idea Perlin/simplex noise is built
    // from) - each shorter-wavelength and smaller-amplitude than the last, each with its
    // own phase so the layers don't all cross zero at the same spot and cancel back into a
    // disguised single bump. Octave amplitudes are normalized (divided by the geometric
    // series sum in octaveAmplitudeNormalizer) so the TOTAL displacement across all octaves
    // still respects WARP_AMPLITUDE_CELLS_CAP exactly regardless of how wavelength/
    // persistence are tuned.
    //
    // WARP_WAVELENGTH_CELLS is deliberately comparable to (slightly under) the region side
    // itself, not some larger "safe-looking" number: the dominant octave must complete a
    // full cycle - genuine direction reversal, not just a bow - within one border's actual
    // length, or the curve still reads as one monotonic bend no matter how much fine
    // texture the higher octaves add on top. WARP_PERSISTENCE (0.55) is high enough that
    // the next 1-2 octaves carry comparable weight instead of fading to barely-visible
    // texture, since real irregularity needs several scales competing, not one dominant
    // curve plus noise. A stricter "keep slope well under 1" rule was considered and
    // rejected: cellsInRegion's brute-force scan + regionOf's direct per-cell test give
    // every cell exactly one region unconditionally regardless of how sharply warpOffset
    // changes between adjacent cells, so a rougher curve carries no actual gap/overlap
    // risk - the only hard invariant left is the total amplitude bound (region/2, see
    // above), which the normalizer still guarantees exactly. Verified empirically, not
    // just reasoned about - see MacroGridConfigTest.warpNeverBleedsPastTheImmediateNeighborRegion,
    // which sweeps real cells and checks the amplitude bound directly regardless of curve
    // roughness.
    private static final double WARP_AMPLITUDE_CELLS_CAP = 8.0;
    private static final double WARP_AMPLITUDE_FRACTION = 0.25;
    private static final double WARP_WAVELENGTH_CELLS = 28.0;
    private static final int WARP_OCTAVE_COUNT = 4;
    private static final double WARP_LACUNARITY = 2.2;
    private static final double WARP_PERSISTENCE = 0.55;
    private static final double WARP_OCTAVE_AMPLITUDE_NORMALIZER = octaveAmplitudeNormalizer();
    private static final long WARP_SALT_X = 0x9E37_79B9_7F4A_7C15L;
    private static final long WARP_SALT_Z = 0xC2B2_AE3D_27D4_EB4FL;
    // At or below this, regionSizeCellsPerSide is so small (only ever synthetic test grids
    // - MacroGridConfig.DEFAULT is 32, and 8 itself is a common small-fixture size across
    // this codebase's own tests) that EVERY cell boundary is already a region boundary;
    // any nonzero warp there could flip a cell into a neighboring region with no
    // "interior, far from any edge" cells left to stay stable - warp amplitude is 0 at or
    // below this threshold instead of chasing an ever-smaller fraction toward it. Boundary
    // is inclusive (<=, not <) on purpose - confirmed via real test breakage the first
    // time WARP_WAVELENGTH_CELLS shrank: a size-8 grid sitting exactly AT this threshold
    // still picked up real (if small) displacement under the shorter, rougher wavelength,
    // flipping a cell count several small-fixture tests asserted exactly.
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
     * The vertical (constant-nominal-x) boundary lines wave as a smooth function of
     * z, and the horizontal (constant-nominal-z) lines wave as a smooth function of
     * x - each axis's warp depends only on the OTHER axis's coordinate, so along any
     * fixed row/column the mapping is just a constant-shifted floor-division (still
     * monotonic and gap-free), and the combined 2D partition stays a clean set of
     * simply-connected, gently-bowed blobs instead of a rigid grid.
     */
    public RegionPos regionOf(MacroCellPos cell, long worldSeed) {
        double warpedX = cell.x() + warpOffset(cell.z(), worldSeed, WARP_SALT_X);
        double warpedZ = cell.z() + warpOffset(cell.x(), worldSeed, WARP_SALT_Z);
        return new RegionPos(
            Math.floorDiv((int) Math.floor(warpedX), regionSizeCellsPerSide),
            Math.floorDiv((int) Math.floor(warpedZ), regionSizeCellsPerSide)
        );
    }

    /** Caps at a fixed amplitude for any real-sized grid ({@code DEFAULT} included), but scales down for a small synthetic test grid instead of overwhelming it. */
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

    /** Independent per-octave phase (not just per-axis) — without this every octave crosses zero at the same along-axis coordinate, which just re-composes into a single disguised bump instead of real fractal detail. */
    private static double octavePhase(long worldSeed, long axisSalt, int octave) {
        long seed = worldSeed ^ axisSalt;
        seed = seed * 31 + octave;
        return ((seed >>> 32) ^ seed) % 1000 / 1000.0 * 2.0 * Math.PI;
    }

    /** Geometric series sum of {@code WARP_PERSISTENCE^octave} over {@link #WARP_OCTAVE_COUNT} octaves — dividing each octave's raw amplitude by this makes the octaves' amplitudes sum back to exactly {@link #warpAmplitudeCells()}, so adding fractal detail never grows the total displacement budget past what {@code WARP_AMPLITUDE_CELLS_CAP} already guarantees is region/2-safe. */
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
     * Scans a bounding box padded by {@link #warpMarginCells()} on every side (instead
     * of the nominal square directly) and keeps whichever candidate cells actually warp
     * into this region - the only way to enumerate a warped region's true membership,
     * since the warp isn't invertible in closed form. Still cheap: at
     * {@code regionSizeCellsPerSide=32}, that's ~1600 candidates tested instead of 1024,
     * not a real cost.
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

    /** The macro cell containing a continuous block-space point — one authoritative home for this floorDiv-by-cell-size math, shared by every caller that needs to go from a real coordinate (a triangle centroid, a query point) to its cell. */
    public MacroCellPos cellContaining(double blockX, double blockZ) {
        return new MacroCellPos(
            Math.floorDiv((int) Math.floor(blockX), macroCellSizeBlocks),
            Math.floorDiv((int) Math.floor(blockZ), macroCellSizeBlocks)
        );
    }

}
