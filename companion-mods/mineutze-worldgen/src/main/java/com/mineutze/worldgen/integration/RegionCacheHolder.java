package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.DecidedFaceStore;
import com.mineutze.worldgen.regionsolve.DecidedVertexStore;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.SeedMeshCache;
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
 * {@link SeedMeshCache} itself. The one piece of runtime state they do
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
 * <p>{@code onLevelLoad} is also where the real {@link SavedDataFaceStore}/{@link
 * SavedDataVertexStore} get constructed (need the loading {@code ServerLevel}, which
 * density function evaluation itself never has) and wired into {@link SeedMeshCache} —
 * see this whole redesign's own plan doc (region-partition removal,
 * doc/decisions/2026-08-29-remove-region-stitching.md) for why face/vertex identity is
 * now decided once, globally, rather than per fixed-shape region.
 *
 * <p>Replaces the older {@code SeedRegionCache}/{@code RegionPos}-based wiring this class
 * used before — a region-shaped cache had its own straight-seam bug at every boundary
 * (see this redesign's own plan doc for the root cause and fix); nothing else in this mod
 * depends on the old wiring, so this is a genuine live cutover, not an additive change. A
 * dev/test world generated before this cutover has no compatible saved region data to
 * migrate — a fresh world (or a fresh region solve) is expected, not a bug.
 */
public final class RegionCacheHolder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long READY_TIMEOUT_SECONDS = 30;
    // Bounds SeedMeshCache's own small per-cell answer cache - NOT the real, permanent
    // data (that's DecidedFaceStore/DecidedVertexStore, unbounded, persisted). An evicted
    // cell is just re-derived via the cheap fast-path lookup next time, never re-solved.
    // Tune from real deployment once one exists, not guessed further here.
    private static final int MAX_CACHED_CELLS = 2_000_000;

    // Covers vanilla's OWN stronghold ring search (ConcentricRingsStructurePlacement,
    // distance=32/spread=3/count=128) - simulated its exact placement formula directly
    // (2026-09-05) and measured its outermost ring at ~24,192 blocks from spawn, reached
    // via 8 concentric rings of increasingly many, increasingly distant candidate points.
    // 25,000 gives a small safety margin above that measured max, not a round guess.
    //
    // WHY this exists at all: that search checks all 128 scattered candidate points in
    // ONE burst (parallel, off-thread) the moment the very first chunk (spawn) is ever
    // generated - before ANY of our own mesh territory has been jointly resolved. Each
    // point, touched in isolation, independently locks in its own tiny neighborhood as
    // PERMANENT; a later query landing in the gap between several of them can find itself
    // boxed in by walls that were each individually fine but were never resolved TOGETHER -
    // a real, observed crash this exact session (worldSeed=42, TriangleIdentitySizeAssignment
    // SearchBudget exhausted, thrown from PlayerRespawnLogic during world creation itself).
    // Pre-solving this whole radius as one CONTIGUOUS, step-connected sweep (see
    // preGenerateSpawnArea) means every one of vanilla's 128 points lands inside territory
    // that was already resolved as one internally-consistent blob, never as an isolated
    // single-point commit.
    private static final int PRE_GENERATE_RADIUS_BLOCKS = 25_000;
    // Must stay comfortably under 2x TriangleFieldGeometryRealization's own
    // CLUSTER_GROWTH_CAP_RADIUS_BLOCKS (2500) so adjacent grid points' own cluster-growth
    // neighborhoods always overlap - guarantees the whole swept area ends up as one single
    // connected region (mirrors why an ORGANIC_WALK, which takes small steps, empirically
    // never dead-ends: each new step extends already-decided territory instead of starting
    // fresh in isolation). NOT tuned finer than this margin - real measurement (timing the
    // actual pre-generation pass) is the next step once this is verified working.
    private static final int PRE_GENERATE_STEP_BLOCKS = 2_000;

    private static final CountDownLatch READY = new CountDownLatch(1);
    private static volatile SeedMeshCache current;

    private RegionCacheHolder() {
    }

    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && serverLevel.dimension().equals(Level.OVERWORLD)) {
            long seed = serverLevel.getSeed();
            LOGGER.info("[mineutze_worldgen] Overworld loaded, seed {} — mesh solving starts on first query", seed);
            DecidedFaceStore faceStore = new DecidedFaceStore(new SavedDataFaceStore(serverLevel));
            DecidedVertexStore vertexStore = new DecidedVertexStore(new SavedDataVertexStore(serverLevel));
            current = new SeedMeshCache(
                ModBiomeGraph.OVERWORLD, MacroGridConfig.DEFAULT, seed, faceStore, vertexStore,
                RegionCacheHolder::logCellSolved, MAX_CACHED_CELLS
            );
            preGenerateSpawnArea(current, MacroGridConfig.DEFAULT);
            READY.countDown();
        }
    }

    /**
     * Synchronously sweeps a {@link #PRE_GENERATE_RADIUS_BLOCKS}-radius square around
     * spawn in a {@link #PRE_GENERATE_STEP_BLOCKS}-spaced grid, BEFORE {@link #READY}
     * opens — see {@link #PRE_GENERATE_RADIUS_BLOCKS}'s own doc for why this exact radius
     * and why it must happen before anything (including vanilla's own stronghold search)
     * can reach {@link #get()}. Runs on the main thread during {@code LevelEvent.Load}
     * dispatch, same as the rest of this method - a real, one-time multi-second delay at
     * world creation, not a background task, since the whole point is that NOTHING may
     * query biomes in this radius before the sweep finishes.
     */
    private static void preGenerateSpawnArea(SeedMeshCache cache, MacroGridConfig gridConfig) {
        int pointsPerAxis = 2 * (PRE_GENERATE_RADIUS_BLOCKS / PRE_GENERATE_STEP_BLOCKS) + 1;
        LOGGER.info(
            "[mineutze_worldgen] Pre-generating {}x{} points ({} block radius, {} block step) around spawn before opening biome queries...",
            pointsPerAxis, pointsPerAxis, PRE_GENERATE_RADIUS_BLOCKS, PRE_GENERATE_STEP_BLOCKS
        );
        long startNanos = System.nanoTime();
        for (int blockX = -PRE_GENERATE_RADIUS_BLOCKS; blockX <= PRE_GENERATE_RADIUS_BLOCKS; blockX += PRE_GENERATE_STEP_BLOCKS) {
            for (int blockZ = -PRE_GENERATE_RADIUS_BLOCKS; blockZ <= PRE_GENERATE_RADIUS_BLOCKS; blockZ += PRE_GENERATE_STEP_BLOCKS) {
                cache.biomeAt(gridConfig.cellContaining(blockX, blockZ));
            }
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        LOGGER.info("[mineutze_worldgen] Spawn-area pre-generation done in {}ms", elapsedMillis);
    }

    public static SeedMeshCache get() {
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

    private static void logCellSolved(MacroCellPos cell, BiomeId biome, long durationMillis) {
        LOGGER.info(
            "[mineutze_worldgen] Solved cell {},{} on {}: {}, {}ms",
            cell.x(), cell.z(), Thread.currentThread().getName(), biome, durationMillis
        );
    }
}
