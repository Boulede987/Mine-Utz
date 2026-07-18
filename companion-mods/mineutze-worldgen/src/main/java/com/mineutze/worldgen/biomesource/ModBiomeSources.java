package com.mineutze.worldgen.biomesource;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers CustomOverworldBiomeSource's MapCodec so dimension JSON can reference
 * "mineutze_filter:custom_overworld" as a biome_source type. */
public final class ModBiomeSources {

    private static final String MOD_ID = "mineutze_filter";

    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
        DeferredRegister.create(Registries.BIOME_SOURCE, MOD_ID);

    public static final DeferredHolder<MapCodec<? extends BiomeSource>, MapCodec<CustomOverworldBiomeSource>> CUSTOM_OVERWORLD =
        BIOME_SOURCES.register("custom_overworld", () -> CustomOverworldBiomeSource.CODEC);

    private ModBiomeSources() {
    }
}
