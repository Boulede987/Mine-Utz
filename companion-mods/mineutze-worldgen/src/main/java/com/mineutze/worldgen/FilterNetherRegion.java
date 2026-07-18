// GENERATED FILE — do not edit directly. Run generate_filter_region.py to regenerate.
//
// This TerraBlender Region registers each selected biome at the EXACT same climate parameter
// positions that the originating mod used. Mirroring those specific positions is what allows
// TerraBlender to route climate lookups to the correct biome without distorting its natural range.
//
// Why specific positions matter: registering a biome with a single wide Climate.Parameter.span
// (e.g. temperature [0.2, 1.0] instead of the original two narrower spans) makes it dominate
// a huge region of climate space. TerraBlender resolves ties at chunk boundaries, so wide spans
// produce chunk-aligned square biome borders. Using all distinct specific positions keeps
// transitions smooth and natural.
//
// Data pipeline:
//   jar scan → worldgen_data/*.json
//   → merge_biome_parameters.py (dedup, keeps all distinct entries per biome)
//   → worldgen_data/merged/*.json
//   → generate_filter_region.py (reads planner ✓ column, calls build_region here)
//   → this file
package com.mineutze.worldgen;

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

    @FunctionalInterface
    private interface BiomeMapper extends Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> {}

    public FilterNetherRegion(ResourceLocation name, int weight) {
        super(name, RegionType.NETHER, weight);
    }

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        BiomeMapper biomeMapper = mapper::accept;
        addBiomesoplenty(biomeMapper);
        addMinecraft(biomeMapper);
    }

    // ── biomesoplenty ────────────────────────────────────────────────────────

    private void addBiomesoplenty(BiomeMapper mapper) {
        biomesoplentyCrystallineChasm(mapper);
        biomesoplentyEruptingInferno(mapper);
        biomesoplentyUndergrowth(mapper);
        biomesoplentyVisceralHeap(mapper);
        biomesoplentyWitheredAbyss(mapper);
    }

    private void biomesoplentyCrystallineChasm(BiomeMapper mapper) {
        var key = biomeKey("biomesoplenty", "crystalline_chasm");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(-0.5f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.0f
        ), key);
    }

    private void biomesoplentyEruptingInferno(BiomeMapper mapper) {
        var key = biomeKey("biomesoplenty", "erupting_inferno");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(-0.5f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.175f
        ), key);
    }

    private void biomesoplentyUndergrowth(BiomeMapper mapper) {
        var key = biomeKey("biomesoplenty", "undergrowth");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(0.4f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.0f
        ), key);
    }

    private void biomesoplentyVisceralHeap(BiomeMapper mapper) {
        var key = biomeKey("biomesoplenty", "visceral_heap");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.5f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.375f
        ), key);
    }

    private void biomesoplentyWitheredAbyss(BiomeMapper mapper) {
        var key = biomeKey("biomesoplenty", "withered_abyss");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.0f
        ), key);
    }

    // ── minecraft ────────────────────────────────────────────────────────────

    private void addMinecraft(BiomeMapper mapper) {
        minecraftBasaltDeltas(mapper);
        minecraftCrimsonForest(mapper);
        minecraftNetherWastes(mapper);
        minecraftSoulSandValley(mapper);
        minecraftWarpedForest(mapper);
    }

    private void minecraftBasaltDeltas(BiomeMapper mapper) {
        var key = biomeKey("minecraft", "basalt_deltas");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(-0.5f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.175f
        ), key);
    }

    private void minecraftCrimsonForest(BiomeMapper mapper) {
        var key = biomeKey("minecraft", "crimson_forest");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(0.4f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.0f
        ), key);
    }

    private void minecraftNetherWastes(BiomeMapper mapper) {
        var key = biomeKey("minecraft", "nether_wastes");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.0f
        ), key);
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.0f
        ), key);
    }

    private void minecraftSoulSandValley(BiomeMapper mapper) {
        var key = biomeKey("minecraft", "soul_sand_valley");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(-0.5f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.0f
        ), key);
    }

    private void minecraftWarpedForest(BiomeMapper mapper) {
        var key = biomeKey("minecraft", "warped_forest");
        addBiome(mapper, Climate.parameters(
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.5f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            Climate.Parameter.point(0.0f),
            0.375f
        ), key);
    }


    private static ResourceKey<Biome> biomeKey(String namespace, String path) {
        return ResourceKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
