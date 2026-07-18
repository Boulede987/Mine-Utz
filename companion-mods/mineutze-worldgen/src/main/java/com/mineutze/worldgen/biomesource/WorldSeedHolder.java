package com.mineutze.worldgen.biomesource;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Captures the actual per-world seed so CustomOverworldBiomeSource's patch-growth
 * hashing can be seeded from it. BiomeSource.getNoiseBiome only receives quart
 * coordinates and a Climate.Sampler -- neither exposes the level seed directly -- so
 * this listens for the overworld's load event and stashes the seed in a static field
 * for CustomOverworldBiomeSource to read lazily on first query.
 *
 * This is deliberately a plain static holder, not per-instance state: BiomeSource
 * instances are constructed from the dimension codec before the level (and its seed)
 * exists, so there's no instance-scoped place to receive this earlier.
 */
public final class WorldSeedHolder {

    private static volatile long overworldSeed = 0L;
    private static volatile boolean seedCaptured = false;

    private WorldSeedHolder() {
    }

    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel && serverLevel.dimension() == ServerLevel.OVERWORLD) {
            overworldSeed = serverLevel.getSeed();
            seedCaptured = true;
        }
    }

    /** Returns the captured overworld seed, or 0 if the level hasn't loaded yet
     * (should not happen in practice -- getNoiseBiome is only ever called during
     * chunk generation, which cannot start before LevelEvent.Load fires for that level). */
    public static long overworldSeed() {
        return overworldSeed;
    }

    public static boolean isSeedCaptured() {
        return seedCaptured;
    }
}
