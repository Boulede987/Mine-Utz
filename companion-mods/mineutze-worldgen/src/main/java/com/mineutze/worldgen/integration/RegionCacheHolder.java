package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.SeedRegionCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * The custom BiomeSource and (later) the density functions only ever get
 * block/quart coordinates, never a ServerLevel — same constraint as
 * {@link SeedRegionCache} itself. The one piece of runtime state they do
 * need, the world seed, is captured here once when the Overworld loads
 * (LevelEvent.Load fires before any chunk generation can start) and held
 * statically for the rest of that server's lifetime.
 */
public final class RegionCacheHolder {

    private static volatile SeedRegionCache current;

    private RegionCacheHolder() {
    }

    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && serverLevel.dimension().equals(Level.OVERWORLD)) {
            current = new SeedRegionCache(
                ModBiomeGraph.DEFAULT, MacroGridConfig.DEFAULT, serverLevel.getSeed(),
                RegionSolveDefaults.SEED_SPACING_BLOCKS, RegionSolveDefaults.BACKTRACK_DEPTH_CAP
            );
        }
    }

    public static SeedRegionCache get() {
        SeedRegionCache cache = current;
        if (cache == null) {
            throw new IllegalStateException("RegionCacheHolder queried before the Overworld finished loading");
        }
        return cache;
    }
}
