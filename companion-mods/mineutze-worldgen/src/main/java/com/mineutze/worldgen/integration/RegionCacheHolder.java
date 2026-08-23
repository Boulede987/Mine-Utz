package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.SeedRegionCache;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The custom BiomeSource and (later) the density functions only ever get
 * block/quart coordinates, never a ServerLevel — same constraint as
 * {@link SeedRegionCache} itself. The one piece of runtime state they do
 * need, the world seed, is captured here once when the Overworld loads.
 *
 * LevelEvent.Load listeners run synchronously on the main thread, but with
 * hundreds of mods also listening, that dispatch can take a noticeable
 * while — and chunk-generation worker threads don't wait on it, so a
 * worker can reach {@link #get()} before this class's listener has run
 * (observed in practice: IllegalStateException from a fresh world,
 * structure-ring biome sampling for a chunk near spawn racing ahead of
 * the main thread's event dispatch). A CountDownLatch makes the worker
 * thread block briefly instead of crashing — the real fix for "consumer
 * needs to wait for a one-time async initialization", not an event
 * ordering guess.
 *
 * <p>{@code onLevelLoad} is also where the real {@link SavedDataRegionPersistenceStore}
 * gets constructed (needs the loading {@code ServerLevel}, which density
 * function evaluation itself never has) and wired into {@link
 * SeedRegionCache} alongside a bounded in-memory cap — see {@link
 * SeedRegionCache}'s own doc for why an evicted region behaves identically
 * to one still in memory, and {@link SavedDataRegionPersistenceStore}'s own
 * doc for why this isn't a repeat of the older, deleted, independently-
 * tracked SavedData cache design.
 */
public final class RegionCacheHolder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long READY_TIMEOUT_SECONDS = 30;
    // ~23MB/region at MacroGridConfig.DEFAULT's real cell resolution (measured directly)
    // - 64 regions is ~1.5GB, a real but bounded slice of a
    // typical modded server's heap, generous enough not to thrash during ordinary
    // exploration. Tune from real deployment once one exists, not guessed further here.
    private static final int MAX_CACHED_REGIONS = 64;

    private static final CountDownLatch READY = new CountDownLatch(1);
    private static volatile SeedRegionCache current;

    private RegionCacheHolder() {
    }

    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && serverLevel.dimension().equals(Level.OVERWORLD)) {
            long seed = serverLevel.getSeed();
            LOGGER.info("[mineutze_worldgen] Overworld loaded, seed {} — region solving starts on first query", seed);
            current = new SeedRegionCache(
                ModBiomeGraph.DEFAULT, MacroGridConfig.DEFAULT, seed, RegionCacheHolder::logRegionSolved,
                new SavedDataRegionPersistenceStore(serverLevel), MAX_CACHED_REGIONS
            );
            READY.countDown();
        }
    }

    public static SeedRegionCache get() {
        awaitReady();
        return current;
    }

    private static void awaitReady() {
        try {
            if (!READY.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                    "Overworld never finished loading within " + READY_TIMEOUT_SECONDS + "s - RegionCacheHolder was never initialized"
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the Overworld to finish loading", e);
        }
    }

    private static void logRegionSolved(RegionPos region, RegionSolveResult result, long durationMillis) {
        LOGGER.info(
            "[mineutze_worldgen] Solved region {},{} on {}: {} cells, {} dropped demand(s), {}ms",
            region.x(), region.z(), Thread.currentThread().getName(),
            result.assignment().size(), result.droppedDemandCount(), durationMillis
        );
        result.warnings().forEach(warning -> LOGGER.warn("[mineutze_worldgen] region {},{}: {}", region.x(), region.z(), warning));
    }
}
