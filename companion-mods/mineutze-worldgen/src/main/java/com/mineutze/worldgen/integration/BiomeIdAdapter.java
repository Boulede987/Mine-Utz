package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import net.minecraft.resources.ResourceLocation;

/**
 * The only place BiomeId (worldgen-core's Minecraft-free identifier) and
 * ResourceLocation (the real registry identifier) meet. worldgen-core stays
 * free of Minecraft/NeoForge classes on purpose — see worldgen-core's
 * build.gradle — so its algorithms are plain, fast-testable JUnit.
 */
public final class BiomeIdAdapter {

    private BiomeIdAdapter() {
    }

    public static BiomeId toBiomeId(ResourceLocation location) {
        return new BiomeId(location.getNamespace(), location.getPath());
    }

    public static ResourceLocation toResourceLocation(BiomeId id) {
        return ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
    }
}
