package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reports whichever biome the region solver assigned to a column's
 * macro-cell — nothing here samples climate noise to pick a biome (doc/
 * ARCHITECTURE.md "What replaces vanilla's multi-noise biome source").
 * Registered via {@link ModBiomeSources} and selected by the
 * data/minecraft/dimension/overworld.json datapack override.
 */
public class ModOverworldBiomeSource extends BiomeSource {

    /**
     * {@code RecordCodecBuilder}'s own {@code .group()}/{@code .apply()} tuple machinery
     * tops out well short of 48 fields (its generated {@code Products} interfaces only go
     * up to ~16) — so instead of one hardcoded {@code RegistryOps.retrieveElement(Biomes.X)}
     * per biome (this class's previous 6-biome-only version), this is a hand-written
     * {@link MapCodec} that walks {@link ModBiomeGraph#OVERWORLD}'s own node set and
     * resolves each one's {@code Holder<Biome>} through {@link RegistryOps}'s registry
     * context directly ({@link RegistryOps#getter}/{@link HolderGetter#get}) in a loop —
     * the exact same registry-lookup primitive {@code retrieveElement} itself uses per
     * field under the hood, just for an arbitrary, runtime-sized list instead of a fixed
     * tuple. Fieldless like {@code retrieveElement}'s own fields: nothing is actually read
     * from or written to the serialized data, every biome here is resolved purely from
     * {@code ModBiomeGraph.OVERWORLD}'s own compile-time-known node set.
     */
    public static final MapCodec<ModOverworldBiomeSource> CODEC = mapCodecFor(overworldBiomeKeys());

    private final Map<ResourceLocation, Holder<Biome>> biomesById;

    private ModOverworldBiomeSource(Map<ResourceLocation, Holder<Biome>> biomesById) {
        this.biomesById = biomesById;
    }

    private static List<ResourceKey<Biome>> overworldBiomeKeys() {
        return ModBiomeGraph.OVERWORLD.nodes().stream()
            .map(BiomeNode::id)
            .map(BiomeIdAdapter::toResourceLocation)
            .map(location -> ResourceKey.create(Registries.BIOME, location))
            .toList();
    }

    private static MapCodec<ModOverworldBiomeSource> mapCodecFor(List<ResourceKey<Biome>> keys) {
        return new MapCodec<>() {
            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return Stream.empty(); // fieldless, same as retrieveElement's own fields
            }

            @Override
            public <T> DataResult<ModOverworldBiomeSource> decode(DynamicOps<T> ops, MapLike<T> input) {
                if (!(ops instanceof RegistryOps<T> registryOps)) {
                    return DataResult.error(() -> "ModOverworldBiomeSource.CODEC requires RegistryOps (biome registry context), got " + ops);
                }
                Optional<HolderGetter<Biome>> getter = registryOps.getter(Registries.BIOME);
                if (getter.isEmpty()) {
                    return DataResult.error(() -> "ModOverworldBiomeSource.CODEC: biome registry unavailable in this RegistryOps context");
                }
                Map<ResourceLocation, Holder<Biome>> biomes = new LinkedHashMap<>();
                for (ResourceKey<Biome> key : keys) {
                    Optional<Holder.Reference<Biome>> resolved = getter.get().get(key);
                    if (resolved.isEmpty()) {
                        return DataResult.error(() -> "ModOverworldBiomeSource.CODEC: no registered biome for " + key
                            + " - ModBiomeGraph.OVERWORLD names a biome this world's datapacks don't actually register");
                    }
                    biomes.put(key.location(), resolved.get());
                }
                return DataResult.success(new ModOverworldBiomeSource(Map.copyOf(biomes)));
            }

            @Override
            public <T> RecordBuilder<T> encode(ModOverworldBiomeSource input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
                return prefix; // nothing to encode - same as retrieveElement's own fieldless encode
            }
        };
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
        if (resolved == null) {
            // Should be unreachable: the solver only ever assigns biomes from
            // ModBiomeGraph.OVERWORLD's own node set, which is exactly this class's own
            // registered biome set (see overworldBiomeKeys, sourced from that same graph).
            // No silent fallback (there's no more universal filler to fall back to anyway,
            // see BiomeGraph's own doc) - a biome id this class doesn't recognize means the
            // graph and this registration have drifted apart, a real bug worth surfacing
            // immediately.
            throw new IllegalStateException("Region solver assigned " + biomeId + " at " + cell + ", which ModOverworldBiomeSource has no registered Holder<Biome> for.");
        }
        return resolved;
    }
}
