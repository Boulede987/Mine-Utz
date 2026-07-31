package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/** One solved region's macro-cell -> biome assignment, persisted as its own SavedData (see doc/ARCHITECTURE.md "Cache storage"). */
public class RegionCacheData extends SavedData {

    private static final String CELL_X_KEY = "x";
    private static final String CELL_Z_KEY = "z";
    private static final String CELL_BIOME_KEY = "biome";
    private static final String CELLS_KEY = "cells";

    private final Map<MacroCellPos, BiomeId> assignment;

    public RegionCacheData(Map<MacroCellPos, BiomeId> assignment) {
        this.assignment = Map.copyOf(assignment);
    }

    public Map<MacroCellPos, BiomeId> assignment() {
        return assignment;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag cells = new ListTag();
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            CompoundTag cellTag = new CompoundTag();
            cellTag.putInt(CELL_X_KEY, entry.getKey().x());
            cellTag.putInt(CELL_Z_KEY, entry.getKey().z());
            cellTag.putString(CELL_BIOME_KEY, BiomeIdAdapter.toResourceLocation(entry.getValue()).toString());
            cells.add(cellTag);
        }
        tag.put(CELLS_KEY, cells);
        return tag;
    }

    public static RegionCacheData load(CompoundTag tag, HolderLookup.Provider provider) {
        Map<MacroCellPos, BiomeId> assignment = new LinkedHashMap<>();
        ListTag cells = tag.getList(CELLS_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag cellTag = cells.getCompound(i);
            MacroCellPos pos = new MacroCellPos(cellTag.getInt(CELL_X_KEY), cellTag.getInt(CELL_Z_KEY));
            BiomeId biome = BiomeIdAdapter.toBiomeId(ResourceLocation.parse(cellTag.getString(CELL_BIOME_KEY)));
            assignment.put(pos, biome);
        }
        return new RegionCacheData(assignment);
    }
}
