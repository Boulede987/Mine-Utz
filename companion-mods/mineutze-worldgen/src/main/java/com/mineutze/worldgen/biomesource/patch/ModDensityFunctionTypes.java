package com.mineutze.worldgen.biomesource.patch;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers RegionIntentDensityFunction's MapCodec so datapack worldgen JSON can
 * reference "mineutze_filter:region_intent" as a density_function type. Same house
 * style as ModBiomeSources -- see its own comment. */
public final class ModDensityFunctionTypes {

    private static final String MOD_ID = "mineutze_filter";

    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
        DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MOD_ID);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<RegionIntentDensityFunction>> REGION_INTENT =
        DENSITY_FUNCTION_TYPES.register("region_intent", () -> RegionIntentDensityFunction.DATA_CODEC);

    private ModDensityFunctionTypes() {
    }
}
