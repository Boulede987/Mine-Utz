package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Reports whichever biome the region solver assigned to a column's
 * macro-cell — nothing here samples climate noise to pick a biome (doc/
 * ARCHITECTURE.md "What replaces vanilla's multi-noise biome source").
 * Registered via {@link ModBiomeSources} and selected by the
 * data/minecraft/dimension/overworld.json datapack override.
 */
public class ModOverworldBiomeSource extends BiomeSource {

    public static final MapCodec<ModOverworldBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                RegistryOps.retrieveElement(Biomes.DESERT),
                RegistryOps.retrieveElement(Biomes.SAVANNA),
                RegistryOps.retrieveElement(Biomes.BADLANDS),
                RegistryOps.retrieveElement(Biomes.OCEAN),
                RegistryOps.retrieveElement(Biomes.FOREST),
                RegistryOps.retrieveElement(Biomes.PLAINS)
            )
            .apply(instance, instance.stable(ModOverworldBiomeSource::new))
    );

    private final Map<ResourceLocation, Holder<Biome>> biomesById;
    private final Holder<Biome> fallback;

    private ModOverworldBiomeSource(
        Holder<Biome> desert, Holder<Biome> savanna, Holder<Biome> badlands,
        Holder<Biome> ocean, Holder<Biome> forest, Holder<Biome> plains
    ) {
        this.biomesById = Map.of(
            ResourceLocation.fromNamespaceAndPath("minecraft", "desert"), desert,
            ResourceLocation.fromNamespaceAndPath("minecraft", "savanna"), savanna,
            ResourceLocation.fromNamespaceAndPath("minecraft", "badlands"), badlands,
            ResourceLocation.fromNamespaceAndPath("minecraft", "ocean"), ocean,
            ResourceLocation.fromNamespaceAndPath("minecraft", "forest"), forest,
            ResourceLocation.fromNamespaceAndPath("minecraft", "plains"), plains
        );
        // Universal filler in ModBiomeGraph - always a legal fallback if a biome-id lookup ever misses.
        this.fallback = plains;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomesById.values().stream();
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        MacroCellPos cell = new MacroCellPos(
            Math.floorDiv(QuartPos.toBlock(quartX), grid.macroCellSizeBlocks()),
            Math.floorDiv(QuartPos.toBlock(quartZ), grid.macroCellSizeBlocks())
        );

        BiomeId biomeId = RegionCacheHolder.get().biomeAt(cell);
        Holder<Biome> resolved = biomesById.get(BiomeIdAdapter.toResourceLocation(biomeId));
        return resolved != null ? resolved : fallback;
    }
}
