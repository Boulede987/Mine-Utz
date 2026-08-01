package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.WorldgenMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegisterEvent;

public final class ModBiomeSources {

    public static final String CUSTOM_OVERWORLD_ID = "custom_overworld";

    private ModBiomeSources() {
    }

    public static void register(RegisterEvent event) {
        event.register(
            Registries.BIOME_SOURCE,
            helper -> helper.register(ResourceLocation.fromNamespaceAndPath(WorldgenMod.MOD_ID, CUSTOM_OVERWORLD_ID), ModOverworldBiomeSource.CODEC)
        );
    }
}
