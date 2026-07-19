package com.mineutze.worldgen.biomesource;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Set;

/**
 * Author-controlled placement rules for one biome. Shape mirrors the JSON the runtime
 * config loader ({@link BiomeConfigRegistry}) will eventually read from a datapack
 * resource (see DESIGN.md Sec 2.4 / plan Sec "Runtime config delivery") -- not built
 * yet, {@link BiomeConfigRegistry} is hand-maintained scaffolding for now.
 *
 * undergroundLinkedSurfaceBiomes is only non-empty for underground (cave) biomes -- it
 * lists the exact surface biomes this cave biome is allowed under (requirement 3,
 * "exact 1:1 linkage"). Empty for surface biomes.
 *
 * regionFamilyWeights is this biome's desired Tectonic terrain-shape templates (Phase 2,
 * TERRAIN_COHERENCE_DESIGN.md Sec 4b/4c) -- empty for any biome that should stay
 * reactive to real Tectonic terrain rather than declaring shape intent (OCEAN, RIVER,
 * and underground biomes never roll a family; see PatchGrowthEngine.isEligible).
 */
public record BiomeConfig(
    ResourceKey<Biome> id,
    Set<TerrainClass> terrainClasses,
    int sizeMinBlocks,
    int sizeMaxBlocks,
    float weight,
    List<ResourceKey<Biome>> preferredNeighbors,
    List<ResourceKey<Biome>> forbiddenNeighbors,
    List<ResourceKey<Biome>> undergroundLinkedSurfaceBiomes,
    List<RegionFamilyWeight> regionFamilyWeights
) {
}
