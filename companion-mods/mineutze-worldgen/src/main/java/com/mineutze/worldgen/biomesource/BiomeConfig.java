package com.mineutze.worldgen.biomesource;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Set;

/**
 * Author-controlled placement rules for one biome. Mirrors the {@code BiomeConfig}
 * dataclass in pythonScripts/worldgen/patch_engine.py and the JSON shape the runtime
 * config loader ({@link BiomeConfigRegistry}) will eventually read from a datapack
 * resource (see DESIGN.md Sec 2.4 / plan Sec "Runtime config delivery").
 *
 * undergroundLinkedSurfaceBiomes is only non-empty for underground (cave) biomes -- it
 * lists the exact surface biomes this cave biome is allowed under (requirement 3,
 * "exact 1:1 linkage"). Empty for surface biomes.
 */
public record BiomeConfig(
    ResourceKey<Biome> id,
    Set<TerrainClass> terrainClasses,
    int sizeMinBlocks,
    int sizeMaxBlocks,
    float weight,
    List<ResourceKey<Biome>> preferredNeighbors,
    List<ResourceKey<Biome>> forbiddenNeighbors,
    List<ResourceKey<Biome>> undergroundLinkedSurfaceBiomes
) {
}
