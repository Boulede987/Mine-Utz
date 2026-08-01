package com.mineutze.worldgen.integration;

/** Placeholders, same as the grid constants in doc/ARCHITECTURE.md - tune from logged solve output. Shared by every entry point that solves regions. */
final class RegionSolveDefaults {

    static final int SEED_SPACING_BLOCKS = 300;
    static final int BACKTRACK_DEPTH_CAP = 50;

    private RegionSolveDefaults() {
    }
}
