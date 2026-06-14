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

public class FilterNetherRegion extends Region {

    public FilterNetherRegion(ResourceLocation name, int weight) {
        super(name, RegionType.NETHER, weight);
    }

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(-0.5f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.175f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "basalt_deltas"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "nether_wastes"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(-0.5f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "soul_sand_valley"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.5f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.375f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "warped_forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(0.4f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("minecraft", "crimson_forest"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(-0.5f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "crystalline_chasm"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(-0.5f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.175f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "erupting_inferno"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(0.4f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "undergrowth"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.5f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.375f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "visceral_heap"))
        );

        addBiome(mapper,
            Climate.parameters(
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                Climate.Parameter.point(0.0f),
                0.0f
            ),
            ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "withered_abyss"))
        );
    }
}
