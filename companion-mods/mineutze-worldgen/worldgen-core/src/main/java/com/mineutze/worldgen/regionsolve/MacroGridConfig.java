package com.mineutze.worldgen.regionsolve;

import java.util.ArrayList;
import java.util.List;

/**
 * The macro-cell/solve-region grid the constraint solver operates on. See
 * doc/ARCHITECTURE.md "Decided" — 64-block (4x4 chunk) cells are a
 * placeholder tuned from live testing (128-block cells read as far too
 * coarse in-game), not derived here. Region is 32x32 cells (2048 blocks):
 * at 16x16 (1024 blocks), a single max-size biome patch (desert's 1000
 * block width needs 244 of a region's cells) could eat 95% of a region,
 * leaving nothing but the region's own square boundary to shape it —
 * patches need real room to breathe for organic growth to matter at all.
 */
public record MacroGridConfig(int macroCellSizeBlocks, int regionSizeCellsPerSide) {

    public static final MacroGridConfig DEFAULT = new MacroGridConfig(64, 32);

    // Region-grid domain warp (doc/KNOWN_ISSUES.md #1's real fix): straight region-solve
    // boundaries read as a hard ruler-straight cut wherever a patch happens to reach one,
    // no amount of patch-level organicizing can hide it (three different pixel-level
    // attempts tried and measured - see RegionSeamBlender's own git history / session
    // notes - none worked, because the STRAIGHT LINE is the region partition itself, not
    // a patch shape). Real fix: the region boundary was never a real geometric feature
    // worth being straight in the first place - warp it, same "displace with noise instead
    // of forcing straight" philosophy EdgeDisplacement already uses for patch edges.
    //
    // AMPLITUDE_CELLS must stay well under half of regionSizeCellsPerSide (32) - the whole
    // rest of this codebase (RegionBoundaryCollector.neighborsOf, SeedRegionCache's
    // concurrent-race locking) assumes a region's true geometric neighbors are only its
    // immediate 8 (Moore) neighbors, never 2 regions away. WAVELENGTH_CELLS >> 32 keeps
    // the warp a slow, smooth bow along a region edge's own length rather than a jittery
    // zigzag - the max per-cell slope is AMPLITUDE*(2*PI/WAVELENGTH) ~= 0.2, far below 1,
    // so warpX/warpZ can never fold or skip a cell.
    private static final double WARP_AMPLITUDE_CELLS_CAP = 3.0;
    private static final double WARP_AMPLITUDE_FRACTION = 0.1;
    private static final double WARP_WAVELENGTH_CELLS = 96.0;
    private static final long WARP_SALT_X = 0x9E37_79B9_7F4A_7C15L;
    private static final long WARP_SALT_Z = 0xC2B2_AE3D_27D4_EB4FL;
    // Below this, regionSizeCellsPerSide is so small (only ever synthetic test grids -
    // MacroGridConfig.DEFAULT is 32) that EVERY cell boundary is already a region
    // boundary; any nonzero warp there could flip a cell into a neighboring region with
    // no "interior, far from any edge" cells left to stay stable - warp amplitude is 0
    // below this threshold instead of chasing an ever-smaller fraction toward it.
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
        if (regionSizeCellsPerSide < WARP_MIN_REGION_SIZE) {
            return 0.0;
        }
        return Math.min(WARP_AMPLITUDE_CELLS_CAP, regionSizeCellsPerSide * WARP_AMPLITUDE_FRACTION);
    }

    private double warpOffset(int alongAxisCoord, long worldSeed, long axisSalt) {
        long seed = worldSeed ^ axisSalt;
        double phase = ((seed >>> 32) ^ seed) % 1000 / 1000.0 * 2.0 * Math.PI;
        return warpAmplitudeCells() * Math.sin(alongAxisCoord * (2.0 * Math.PI / WARP_WAVELENGTH_CELLS) + phase);
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
}
