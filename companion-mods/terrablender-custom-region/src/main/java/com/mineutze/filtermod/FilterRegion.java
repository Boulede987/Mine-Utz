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
    }
}
