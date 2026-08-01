package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.SeedRegionCache;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

/**
 * The custom BiomeSource and (later) the density functions only ever get
 * block/quart coordinates, never a ServerLevel — same constraint as
 * {@link SeedRegionCache} itself. The one piece of runtime state they do
 * need, the world seed, is captured here once when the Overworld loads
 * (LevelEvent.Load fires before any chunk generation can start) and held
 * statically for the rest of that server's lifetime.
 */
public final class RegionCacheHolder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile SeedRegionCache current;

    private RegionCacheHolder() {
    }

    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && serverLevel.dimension().equals(Level.OVERWORLD)) {
            long seed = serverLevel.getSeed();
            LOGGER.info("[mineutze_worldgen] Overworld loaded, seed {} — region solving starts on first query", seed);
            current = new SeedRegionCache(
                ModBiomeGraph.DEFAULT, MacroGridConfig.DEFAULT, seed,
                RegionSolveDefaults.SEED_SPACING_BLOCKS, RegionSolveDefaults.BACKTRACK_DEPTH_CAP,
                RegionCacheHolder::logRegionSolved
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

    private static void logRegionSolved(RegionPos region, RegionSolveResult result) {
        LOGGER.info(
            "[mineutze_worldgen] Solved region {},{}: {} cells, {} warning(s)",
            region.x(), region.z(), result.assignment().size(), result.warnings().size()
        );
        result.warnings().forEach(warning -> LOGGER.warn("[mineutze_worldgen] region {},{}: {}", region.x(), region.z(), warning));
    }
}
