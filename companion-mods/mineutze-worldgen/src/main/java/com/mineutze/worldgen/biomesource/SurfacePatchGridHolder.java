package com.mineutze.worldgen.biomesource;

import com.mineutze.worldgen.biomesource.patch.SurfacePatchGrid;

/**
 * Static holder exposing the live SurfacePatchGrid instance to code that has no other
 * legitimate way to reach it -- mirrors WorldSeedHolder's exact pattern (see its own
 * class comment) for the same underlying reason: a codec-constructed object (there,
 * BiomeSource; here, Phase 2's DensityFunction, Stage 3) is built before the level/
 * generator state it needs exists, so there's no instance-scoped place to receive this
 * earlier. Populated by CustomOverworldBiomeSource.surfacePatchGrid()'s existing
 * double-checked-locking lazy-init, the moment that instance is first created.
 */
public final class SurfacePatchGridHolder {

    private static volatile SurfacePatchGrid overworldSurfacePatchGrid;

    private SurfacePatchGridHolder() {
    }

    public static void set(SurfacePatchGrid grid) {
        overworldSurfacePatchGrid = grid;
    }

    /** Null until CustomOverworldBiomeSource's first getNoiseBiome call lazily creates
     * its SurfacePatchGrid -- callers (Stage 3's RegionIntentDensityFunction) must treat
     * null the same as any other "stay reactive to real Tectonic terrain" case, never
     * throw. */
    public static SurfacePatchGrid overworldSurfacePatchGrid() {
        return overworldSurfacePatchGrid;
    }
}
