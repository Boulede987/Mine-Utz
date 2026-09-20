package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.coherence.PlanSnapshot;
import com.mineutze.worldgen.coherence.WorldBiomePlan;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the world's {@link WorldBiomePlan} (2026-09-20). The biome source only ever gets block
 * coordinates, never a {@code ServerLevel}, so the one piece of state it needs - the plan - is made
 * here once when the Overworld loads and read by chunk-generation worker threads through {@link #get()}.
 *
 * <p>On load: a saved plan is restored verbatim and never recomputed - a world's biomes must not change
 * because the mod, the machine or the catalog did (a changed catalog only earns a loud warning). With no
 * saved plan the world is new: the plan is solved from the world seed, SYNCHRONOUSLY, on the loading
 * thread, before anything can ask for a biome, then saved. That takes tens of seconds on a many-core
 * machine and a few minutes on a small one, once per world; nothing is ever solved while the player
 * explores. The wait is logged every few seconds so a slow machine does not look like a hang.
 *
 * <p>Chunk workers can reach {@link #get()} before the load event handler has run (seen with the old
 * system on a fresh world, with hundreds of mods also listening), so they block on a latch instead of
 * failing; the latch times out only after an hour, far past any real solve.
 */
public final class WorldBiomePlanHolder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long READY_TIMEOUT_MINUTES = 60;
    private static final long HEARTBEAT_SECONDS = 10;

    private static final CountDownLatch READY = new CountDownLatch(1);
    private static volatile WorldBiomePlan current;

    private WorldBiomePlanHolder() {
    }

    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && serverLevel.dimension().equals(Level.OVERWORLD)) {
            current = loadOrCreate(serverLevel);
            READY.countDown();
        }
    }

    public static WorldBiomePlan get() {
        awaitReady();
        return current;
    }

    private static WorldBiomePlan loadOrCreate(ServerLevel level) {
        BiomeGraphData catalog = BiomeGraphJson.loadResource(ModBiomeGraph.class, "/biomegraph/biome-graph.json");
        DimensionDataStorage storage = level.getDataStorage();
        long seed = level.getSeed();

        WorldBiomePlanSavedData saved = storage.get(WorldBiomePlanSavedData.factory(), WorldBiomePlanSavedData.SAVE_NAME);
        if (saved != null) {
            return restore(saved.snapshot(), catalog, seed);
        }

        LOGGER.info("[mineutze_worldgen] New world (seed {}): creating its biome plan before any chunk is generated", seed);
        WorldBiomePlan plan = create(catalog, seed);
        WorldBiomePlanSavedData data = new WorldBiomePlanSavedData(plan.toSnapshot());
        // Minecraft only writes data that is marked dirty; without this the plan would never reach the disk
        // and every start of the world would silently solve it again (found by the first real dev-server run).
        data.setDirty();
        storage.set(WorldBiomePlanSavedData.SAVE_NAME, data);
        storage.save();
        LOGGER.info("[mineutze_worldgen] Saved the biome plan with the world ({})", WorldBiomePlanSavedData.SAVE_NAME);
        return plan;
    }

    private static WorldBiomePlan restore(PlanSnapshot snapshot, BiomeGraphData catalog, long seed) {
        if (snapshot.worldSeed() != seed) {
            throw new IllegalStateException(
                "The saved biome plan was made for world seed " + snapshot.worldSeed() + " but this world's seed is " + seed
                    + " - the plan file belongs to a different world. Refusing to start rather than mix two worlds' biomes."
            );
        }
        WorldBiomePlan plan = WorldBiomePlan.fromSnapshot(snapshot);
        String currentFingerprint = WorldBiomePlan.fingerprintOf(catalog);
        if (!snapshot.catalogFingerprint().equals(currentFingerprint)) {
            LOGGER.warn(
                "[mineutze_worldgen] The biome catalog has changed since this world was created. Keeping the world's saved biome plan unchanged (its terrain must not move); "
                    + "the rules in the current catalog may no longer hold for this world. Plan made with catalog {}, current catalog {}.",
                snapshot.catalogFingerprint(), currentFingerprint
            );
        }
        LOGGER.info(
            "[mineutze_worldgen] Restored the saved biome plan (seed {}, warp strength {}, pattern attempt {})",
            seed, plan.warpStrength(), plan.patternAttempts()
        );
        return plan;
    }

    private static WorldBiomePlan create(BiomeGraphData catalog, long seed) {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        long start = System.nanoTime();
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mineutze-worldgen-progress");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(
            () -> LOGGER.info("[mineutze_worldgen] Still creating the biome plan ({}s elapsed)...", elapsedSeconds(start)), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS
        );
        try {
            WorldBiomePlan plan = WorldBiomePlan.create(catalog, seed, threads, message -> LOGGER.info("[mineutze_worldgen] {} ({}s)", message, elapsedSeconds(start)));
            LOGGER.info("[mineutze_worldgen] Biome plan created in {}s", elapsedSeconds(start));
            return plan;
        } catch (RuntimeException failure) {
            LOGGER.error("[mineutze_worldgen] Could not create the biome plan for world seed {} - the world cannot be generated", seed, failure);
            throw failure;
        } finally {
            heartbeat.shutdownNow();
        }
    }

    private static long elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000L;
    }

    private static void awaitReady() {
        try {
            if (!READY.await(READY_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw new IllegalStateException("The Overworld never finished loading within " + READY_TIMEOUT_MINUTES + " minutes - WorldBiomePlanHolder was never initialized");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the Overworld's biome plan", interrupted);
        }
    }
}
