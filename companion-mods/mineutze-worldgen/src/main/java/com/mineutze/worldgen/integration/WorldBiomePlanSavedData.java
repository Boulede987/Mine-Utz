package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.plan.PlanSnapshot;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * The world's biome plan, saved with the world (2026-09-20). One small compound in the Overworld's
 * data folder ({@code data/mineutze_worldgen_plan.dat}, gzip'd by Minecraft): the raster is the
 * source of truth for every biome the world will ever have, so it is written once, when the world is
 * created, and read back verbatim on every later load - never recomputed. The rest of the snapshot
 * says how the map was made, and which catalog it was made from.
 *
 * <p>Two version numbers, on purpose: {@code nbtSchema} is THIS class's layout of the compound;
 * {@code planSchema} is the {@link PlanSnapshot}'s own format, checked when the plan is restored. An
 * unknown version of either fails loudly - no guessing what an old or future file meant.
 */
final class WorldBiomePlanSavedData extends SavedData {

    static final String SAVE_NAME = "mineutze_worldgen_plan";

    private static final int NBT_SCHEMA = 1;
    private static final String NBT_SCHEMA_KEY = "nbtSchema";
    private static final String PLAN_SCHEMA_KEY = "planSchema";
    private static final String WORLD_SEED_KEY = "worldSeed";
    private static final String CATALOG_FINGERPRINT_KEY = "catalogFingerprint";
    private static final String GRID_WIDTH_KEY = "gridWidth";
    private static final String GRID_HEIGHT_KEY = "gridHeight";
    private static final String ATOM_WIDTH_KEY = "atomWidthBlocks";
    private static final String WARP_STRENGTH_KEY = "warpStrength";
    private static final String PATTERN_ATTEMPTS_KEY = "patternAttempts";
    private static final String STRENGTHS_TRIED_KEY = "strengthsTried";
    private static final String PALETTE_KEY = "palette";
    private static final String RASTER_SIZE_KEY = "rasterSize";
    private static final String RASTER_KEY = "raster";

    private final PlanSnapshot snapshot;

    WorldBiomePlanSavedData(PlanSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    PlanSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Only {@code DimensionDataStorage#get} is ever used with this factory, and it never calls the
     * "create an empty one" half when no file exists: a missing plan means "make one", decided by the
     * caller, not a silently empty instance.
     */
    static SavedData.Factory<WorldBiomePlanSavedData> factory() {
        return new SavedData.Factory<>(
            () -> {
                throw new IllegalStateException("WorldBiomePlanSavedData: there is no plan file to restore - the caller must create the plan");
            },
            WorldBiomePlanSavedData::load
        );
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt(NBT_SCHEMA_KEY, NBT_SCHEMA);
        tag.putInt(PLAN_SCHEMA_KEY, snapshot.schemaVersion());
        tag.putLong(WORLD_SEED_KEY, snapshot.worldSeed());
        tag.putString(CATALOG_FINGERPRINT_KEY, snapshot.catalogFingerprint());
        tag.putInt(GRID_WIDTH_KEY, snapshot.gridWidth());
        tag.putInt(GRID_HEIGHT_KEY, snapshot.gridHeight());
        tag.putDouble(ATOM_WIDTH_KEY, snapshot.atomWidthBlocks());
        tag.putDouble(WARP_STRENGTH_KEY, snapshot.warpStrength());
        tag.putInt(PATTERN_ATTEMPTS_KEY, snapshot.patternAttempts());
        tag.putInt(STRENGTHS_TRIED_KEY, snapshot.strengthsTried());
        ListTag palette = new ListTag();
        for (String biome : snapshot.palette()) {
            palette.add(StringTag.valueOf(biome));
        }
        tag.put(PALETTE_KEY, palette);
        tag.putInt(RASTER_SIZE_KEY, snapshot.rasterSize());
        tag.putByteArray(RASTER_KEY, snapshot.raster().clone());
        return tag;
    }

    static WorldBiomePlanSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        int nbtSchema = tag.getInt(NBT_SCHEMA_KEY);
        if (nbtSchema != NBT_SCHEMA) {
            throw new IllegalStateException(
                "WorldBiomePlanSavedData: the plan file has layout version " + nbtSchema + " but this mod reads version " + NBT_SCHEMA + " - it was written by a different version of the mod"
            );
        }
        for (String required : List.of(PLAN_SCHEMA_KEY, WORLD_SEED_KEY, CATALOG_FINGERPRINT_KEY, PALETTE_KEY, RASTER_SIZE_KEY, RASTER_KEY)) {
            if (!tag.contains(required)) {
                throw new IllegalStateException("WorldBiomePlanSavedData: the plan file is missing '" + required + "' - it is damaged, refusing to load an empty world");
            }
        }
        ListTag paletteTag = tag.getList(PALETTE_KEY, Tag.TAG_STRING);
        List<String> palette = new ArrayList<>();
        for (int index = 0; index < paletteTag.size(); index++) {
            palette.add(paletteTag.getString(index));
        }
        return new WorldBiomePlanSavedData(new PlanSnapshot(
            tag.getInt(PLAN_SCHEMA_KEY), tag.getLong(WORLD_SEED_KEY), tag.getString(CATALOG_FINGERPRINT_KEY), tag.getInt(GRID_WIDTH_KEY), tag.getInt(GRID_HEIGHT_KEY),
            tag.getDouble(ATOM_WIDTH_KEY), tag.getDouble(WARP_STRENGTH_KEY), tag.getInt(PATTERN_ATTEMPTS_KEY), tag.getInt(STRENGTHS_TRIED_KEY), palette,
            tag.getInt(RASTER_SIZE_KEY), tag.getByteArray(RASTER_KEY).clone()
        ));
    }
}
