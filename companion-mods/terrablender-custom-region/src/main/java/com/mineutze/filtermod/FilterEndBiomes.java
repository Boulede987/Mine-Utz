package com.mineutze.filtermod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import terrablender.api.EndBiomeRegistry;

public class FilterEndBiomes {

    public static void setup() {
        EndBiomeRegistry.registerHighlandsBiome(key("biomesoplenty", "end_corruption"), 3);
        EndBiomeRegistry.registerHighlandsBiome(key("biomesoplenty", "end_reef"), 6);
        EndBiomeRegistry.registerHighlandsBiome(key("biomesoplenty", "end_wilds"), 9);
    }

    private static ResourceKey<Biome> key(String ns, String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(ns, path));
    }
}
