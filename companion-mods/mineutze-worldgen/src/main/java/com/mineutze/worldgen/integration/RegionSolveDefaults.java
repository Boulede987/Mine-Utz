package com.mineutze.worldgen.integration;

/** Placeholders, same as the grid constants in doc/ARCHITECTURE.md - tune from logged solve output. Shared by every entry point that solves regions. */
final class RegionSolveDefaults {

    // 300 was set when macro-cell/region sizes were much smaller and never revisited
    // after they grew - at 300, a region got ~64 seed attempts, but ModBiomeGraph's
    // biomes each need 22-244 cells (of a region's 1024), so most seeds couldn't
    // possibly fit: diagnosed via ~17700 "backtrack depth cap reached" warnings across
    // a 180-region test sweep. 550 approximates region_area / average_biome_patch_area
    // (~16 seeds/region, each with realistic room) and cut that to ~2300 in the same
    // sweep. Still a placeholder - tune further from logged solve output.
    static final int SEED_SPACING_BLOCKS = 550;
    static final int BACKTRACK_DEPTH_CAP = 50;

    private RegionSolveDefaults() {
    }
}
