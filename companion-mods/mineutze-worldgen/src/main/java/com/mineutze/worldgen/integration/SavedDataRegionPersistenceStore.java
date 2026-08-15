package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.RegionPersistenceStore;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NeoForge {@code SavedData}-backed {@link RegionPersistenceStore} — one
 * file per region, same {@code "mineutze_worldgen_region_" + x + "_" + z}
 * naming as the earlier (deleted) {@code RegionCacheManager}/{@code
 * RegionCacheData} design, which chose that granularity deliberately (see
 * {@link com.mineutze.worldgen.regionsolve.SeedRegionCache}'s own doc for
 * why that design's actual bug wasn't the file layout — it was tracking
 * "solved" independently of the in-memory cache). This class only ever
 * answers "do you have this region's already-solved result" — it never
 * solves anything itself, on purpose: {@code load} uses {@link
 * net.minecraft.world.level.saveddata.DimensionDataStorage#get} (a pure
 * lookup, returns {@code null} if absent, never invokes a factory
 * supplier), not {@code computeIfAbsent} — the old design's {@code
 * getOrSolve} used {@code computeIfAbsent} with a factory whose supplier
 * itself re-solved on a miss, which is a second bug independent of the
 * cache-divergence one: a bare "is this on disk" check could silently
 * trigger an entirely separate solve. {@link SeedRegionCache} is the only
 * thing that ever decides whether a region needs solving.
 *
 * <p>Reuses {@link BiomeIdAdapter} for the {@code BiomeId}/{@code
 * ResourceLocation} round trip rather than reinventing it — the same
 * conversion point the deleted design already used.
 */
public final class SavedDataRegionPersistenceStore implements RegionPersistenceStore {

    // Never actually invoked - DimensionDataStorage.get(factory, name) is a pure lookup
    // that only ever calls the DESERIALIZER (RegionCacheData::load) on a hit, never the
    // supplier. If this ever fires, that contract changed and load() below needs fixing,
    // not a silent wrong answer.
    private static final SavedData.Factory<RegionCacheData> LOAD_ONLY_FACTORY = new SavedData.Factory<>(
        () -> {
            throw new AssertionError("SavedData.Factory supplier invoked via a pure load - DimensionDataStorage.get(...)'s contract must have changed");
        },
        RegionCacheData::load
    );

    private final ServerLevel level;

    public SavedDataRegionPersistenceStore(ServerLevel level) {
        this.level = level;
    }

    @Override
    public Optional<RegionSolveResult> load(RegionPos region) {
        RegionCacheData data = level.getDataStorage().get(LOAD_ONLY_FACTORY, saveName(region));
        return Optional.ofNullable(data).map(RegionCacheData::toResult);
    }

    @Override
    public void save(RegionPos region, RegionSolveResult result) {
        SavedData.Factory<RegionCacheData> factory = new SavedData.Factory<>(() -> RegionCacheData.from(result), RegionCacheData::load);
        // computeIfAbsent, not a raw overwrite - SeedRegionCache only ever calls save()
        // once per region, immediately after its one-and-only real solve, so there's
        // nothing meaningful to overwrite; this just needs to get the fresh data
        // registered (and therefore marked for the next world save) the first time.
        level.getDataStorage().computeIfAbsent(factory, saveName(region));
    }

    private static String saveName(RegionPos region) {
        return "mineutze_worldgen_region_" + region.x() + "_" + region.z();
    }

    /** One solved region's full {@link RegionSolveResult}, persisted as its own SavedData. */
    private static final class RegionCacheData extends SavedData {

        // Bumped only if this NBT shape ever changes - a real migration path belongs
        // here once that happens, not a silent best-effort re-read. The deleted design
        // had no version field at all; cheap enough to add this time.
        private static final int SCHEMA_VERSION = 1;
        private static final String SCHEMA_VERSION_KEY = "schemaVersion";
        private static final String DROPPED_DEMAND_COUNT_KEY = "droppedDemandCount";
        private static final String WARNINGS_KEY = "warnings";
        private static final String WARNING_KEY = "warning";
        private static final String CELLS_KEY = "cells";
        private static final String CELL_X_KEY = "x";
        private static final String CELL_Z_KEY = "z";
        private static final String CELL_BIOME_KEY = "biome";

        private final RegionSolveResult result;

        private RegionCacheData(RegionSolveResult result) {
            this.result = result;
        }

        static RegionCacheData from(RegionSolveResult result) {
            return new RegionCacheData(result);
        }

        RegionSolveResult toResult() {
            return result;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            tag.putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION);
            tag.putInt(DROPPED_DEMAND_COUNT_KEY, result.droppedDemandCount());

            ListTag cells = new ListTag();
            for (Map.Entry<MacroCellPos, BiomeId> entry : result.assignment().entrySet()) {
                CompoundTag cellTag = new CompoundTag();
                cellTag.putInt(CELL_X_KEY, entry.getKey().x());
                cellTag.putInt(CELL_Z_KEY, entry.getKey().z());
                cellTag.putString(CELL_BIOME_KEY, BiomeIdAdapter.toResourceLocation(entry.getValue()).toString());
                cells.add(cellTag);
            }
            tag.put(CELLS_KEY, cells);

            ListTag warnings = new ListTag();
            for (String warning : result.warnings()) {
                CompoundTag warningTag = new CompoundTag();
                warningTag.putString(WARNING_KEY, warning);
                warnings.add(warningTag);
            }
            tag.put(WARNINGS_KEY, warnings);

            return tag;
        }

        static RegionCacheData load(CompoundTag tag, HolderLookup.Provider provider) {
            int schemaVersion = tag.getInt(SCHEMA_VERSION_KEY);
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalStateException(
                    "SavedDataRegionPersistenceStore: unknown schema version " + schemaVersion
                        + " (expected " + SCHEMA_VERSION + ") - a real migration belongs here once this ever changes"
                );
            }

            Map<MacroCellPos, BiomeId> assignment = new LinkedHashMap<>();
            ListTag cells = tag.getList(CELLS_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < cells.size(); i++) {
                CompoundTag cellTag = cells.getCompound(i);
                MacroCellPos pos = new MacroCellPos(cellTag.getInt(CELL_X_KEY), cellTag.getInt(CELL_Z_KEY));
                BiomeId biome = BiomeIdAdapter.toBiomeId(ResourceLocation.parse(cellTag.getString(CELL_BIOME_KEY)));
                assignment.put(pos, biome);
            }

            List<String> warnings = new ArrayList<>();
            ListTag warningsTag = tag.getList(WARNINGS_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < warningsTag.size(); i++) {
                warnings.add(warningsTag.getCompound(i).getString(WARNING_KEY));
            }

            return new RegionCacheData(new RegionSolveResult(assignment, warnings, tag.getInt(DROPPED_DEMAND_COUNT_KEY)));
        }
    }
}
