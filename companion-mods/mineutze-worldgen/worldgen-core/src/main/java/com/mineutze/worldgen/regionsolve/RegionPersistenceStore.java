package com.mineutze.worldgen.regionsolve;

import java.util.Optional;

/**
 * Optional durable backing for {@link SeedRegionCache} — lets a solved
 * region survive eviction from the in-memory cache without losing the work.
 * Deliberately NOT a second, independently-consulted "is this region
 * solved" source: {@link SeedRegionCache} only ever checks a store through
 * the exact same code path it checks its own in-memory map through (see its
 * own doc), so a solved-but-evicted region behaves identically to one still
 * in memory. An earlier design ({@code RegionCacheManager}/{@code
 * RegionCacheData}, deleted) got this wrong — it tracked "solved" via its
 * own independent disk lookup, which could and did diverge from what the
 * in-memory cache actually knew, producing a different boundary-stitch
 * result than what real generation had already committed to. This interface
 * exists specifically so that mistake isn't repeatable: a store only ever
 * answers "do you have this region's ALREADY-SOLVED result," never "should
 * this region be solved."
 *
 * <p>{@code worldgen-core} has no Minecraft/NeoForge dependency by design
 * (see {@link com.mineutze.worldgen.biomegraph.BiomeId}'s own doc) — a real
 * implementation backed by NeoForge's {@code SavedData} lives in the mod's
 * {@code integration} module instead, the same split already used for
 * {@code BiomeId}/{@code BiomeIdAdapter}.
 */
public interface RegionPersistenceStore {

    /** No persistence at all — every existing {@link SeedRegionCache} constructor defaults to this, so nothing that doesn't opt in changes behavior. */
    RegionPersistenceStore NONE = new RegionPersistenceStore() {
        @Override
        public Optional<RegionSolveResult> load(RegionPos region) {
            return Optional.empty();
        }

        @Override
        public void save(RegionPos region, RegionSolveResult result) {
        }
    };

    /** Empty if this region was never solved, or its persisted result was never written/is unavailable — never "not yet, but go solve it." */
    Optional<RegionSolveResult> load(RegionPos region);

    /** Called once, immediately after a region is freshly solved — durable before eviction ever has a chance to touch it. */
    void save(RegionPos region, RegionSolveResult result);
}
