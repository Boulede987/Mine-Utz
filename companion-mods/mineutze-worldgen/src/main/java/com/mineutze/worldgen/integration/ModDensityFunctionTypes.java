package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.WorldgenMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegisterEvent;

public final class ModDensityFunctionTypes {

    public static final String BIOME_SHAPE_OFFSET_ID = "biome_shape_offset";

    private ModDensityFunctionTypes() {
    }

    public static void register(RegisterEvent event) {
        event.register(
            Registries.DENSITY_FUNCTION_TYPE,
            helper -> helper.register(
                ResourceLocation.fromNamespaceAndPath(WorldgenMod.MOD_ID, BIOME_SHAPE_OFFSET_ID),
                BiomeShapeOffsetDensityFunction.CODEC
            )
        );
    }
}
