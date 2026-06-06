package com.mineutze.filtermod;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.api.RegionType;

import java.util.function.Consumer;

public class FilterRegion extends Region {

    public FilterRegion(ResourceLocation name, int weight) {
        super(name, RegionType.OVERWORLD, weight);
    }

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, -0.11f),
                Climate.Parameter.span(-0.2225f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 0.26666668f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomeswevegone", "dacite_shore"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(-0.1f, 0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomeswevegone", "maple_taiga"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(0.1f, 0.3f),
                Climate.Parameter.span(0.03f, 1.0f),
                Climate.Parameter.span(-0.78f, 0.05f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomeswevegone", "rose_fields"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, 0.2f),
                Climate.Parameter.span(-1.0f, 0.1f),
                Climate.Parameter.span(0.03f, 1.0f),
                Climate.Parameter.span(-0.78f, 0.05f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomeswevegone", "sakura_grove"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(-0.1f, 0.3f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "snowblossom_grove"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "snowy_maple_woods"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(0.3f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "forested_field"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "maple_woods"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(-0.1f, 0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "pumpkin_patch"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(-0.35f, -0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "jacaranda_glade"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(-1.0f, -0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "lavender_field"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(-0.1f, 0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "orchard"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(-1.0f, -0.35f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "pasture"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(0.1f, 0.3f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "rocky_shrubland"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, -0.05f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "shrubland"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 1.0f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "floodplain"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(-1.0f, -0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "mediterranean_forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "rainforest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "rocky_rainforest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.05f, -0.455f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "deep_frozen_ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.455f, -0.19f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "frozen_ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 0.05f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "frozen_river"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 0.2f),
                Climate.Parameter.span(-0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, -0.375f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "grove"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(-1.0f, -0.35f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "ice_spikes"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, -0.11f),
                Climate.Parameter.span(-0.2225f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 0.2666f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "snowy_beach"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(-1.0f, 0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "snowy_plains"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 0.2f),
                Climate.Parameter.span(-1.0f, -0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, -0.375f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "snowy_slopes"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.45f),
                Climate.Parameter.span(-0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "snowy_taiga"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.15f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "taiga"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.15f),
                Climate.Parameter.span(-1.0f, -0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(0.45f, 0.55f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "windswept_gravelly_hills"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.455f, -0.19f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "cold_ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.05f, -0.455f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "deep_cold_ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 0.2f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, -0.375f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(0.4f, 0.9333f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "frozen_peaks"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 0.2f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, -0.375f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.9333f, -0.4f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "jagged_peaks"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, 0.2f),
                Climate.Parameter.span(-1.0f, 0.3f),
                Climate.Parameter.span(0.03f, 1.0f),
                Climate.Parameter.span(-0.7799f, 0.05f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "meadow"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(0.3f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "old_growth_pine_taiga"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, -0.15f),
                Climate.Parameter.span(0.3f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, -0.05f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "old_growth_spruce_taiga"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 0.2f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(0.45f, 0.55f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "windswept_forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 0.2f),
                Climate.Parameter.span(-1.0f, 0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(0.45f, 0.55f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "windswept_hills"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, 0.55f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, -0.11f),
                Climate.Parameter.span(-0.2225f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 0.2666f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "beach"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(0.1f, 0.3f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "birch_forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, 0.2f),
                Climate.Parameter.span(-1.0f, -0.1f),
                Climate.Parameter.span(0.03f, 1.0f),
                Climate.Parameter.span(-0.7799f, 0.05f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(0.2666f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "cherry_grove"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(0.3f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "dark_forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.05f, -0.455f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "deep_ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(-1.0f, -0.35f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, -0.05f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "flower_forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, 0.55f),
                Climate.Parameter.span(-0.1f, 0.3f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.2f, -1.05f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "mushroom_fields"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.455f, -0.19f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(0.1f, 0.3f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "old_growth_birch_forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, 0.55f),
                Climate.Parameter.span(-1.0f, 0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "plains"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, -0.11f),
                Climate.Parameter.span(-1.0f, -0.2225f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "stony_shore"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 0.2f),
                Climate.Parameter.span(-1.0f, -0.35f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "sunflower_plains"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, 0.2f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.11f, 1.0f),
                Climate.Parameter.span(0.55f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "swamp"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(0.3f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "bamboo_jungle"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.05f, -0.455f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "deep_lukewarm_ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "jungle"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.455f, -0.19f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "lukewarm_ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.45f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 0.05f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "river"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(-1.0f, -0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "savanna"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(-1.0f, -0.1f),
                Climate.Parameter.span(-0.11f, 1.0f),
                Climate.Parameter.span(-1.0f, 0.05f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "savanna_plateau"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(0.1f, 0.3f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "sparse_jungle"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 0.55f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, -0.375f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.9333f, 0.9333f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "stony_peaks"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-0.15f, 1.0f),
                Climate.Parameter.span(-1.0f, 0.3f),
                Climate.Parameter.span(-0.19f, 0.03f),
                Climate.Parameter.span(0.45f, 0.55f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "windswept_savanna"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.55f, 1.0f),
                Climate.Parameter.span(-1.0f, 0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 0.05f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "badlands"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.55f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "desert"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.55f, 1.0f),
                Climate.Parameter.span(-1.0f, -0.1f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 0.05f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-0.05f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "eroded_badlands"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.2f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-0.11f, 1.0f),
                Climate.Parameter.span(0.55f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "mangrove_swamp"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.55f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.05f, -0.19f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "warm_ocean"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.55f, 1.0f),
                Climate.Parameter.span(0.1f, 1.0f),
                Climate.Parameter.span(-0.19f, 1.0f),
                Climate.Parameter.span(-1.0f, 0.05f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "wooded_badlands"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.7f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.2f, 0.9f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "glowing_grotto"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.8f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.2f, 0.9f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "spider_nest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, -0.375f),
                Climate.Parameter.point(1.1f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "deep_dark"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.8f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.2f, 0.9f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "dripstone_caves"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.7f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.2f, 0.9f),
                Climate.Parameter.span(-1.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "lush_caves"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.6f, 1.0f),
                Climate.Parameter.span(-1.0f, -0.4f),
                Climate.Parameter.span(0.5f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.2f, 0.9f),
                Climate.Parameter.span(0.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("undergroundworlds", "underground_desert"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(0.4f, 1.0f),
                Climate.Parameter.span(0.4f, 1.0f),
                Climate.Parameter.span(0.5f, 1.0f),
                Climate.Parameter.span(-1.0f, 1.0f),
                Climate.Parameter.span(0.2f, 0.9f),
                Climate.Parameter.span(0.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("undergroundworlds", "underground_jungle"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.span(-1.0f, -0.5f),
                Climate.Parameter.span(0.0f, 1.0f),
                Climate.Parameter.span(0.5f, 1.0f),
                Climate.Parameter.span(0.0f, 1.0f),
                Climate.Parameter.span(0.2f, 0.9f),
                Climate.Parameter.span(0.0f, 1.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("undergroundworlds", "underground_tundra"))
        );
    }
}
