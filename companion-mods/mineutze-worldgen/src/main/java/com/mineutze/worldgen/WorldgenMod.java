package com.mineutze.worldgen;

import com.mineutze.worldgen.biomesource.HeightSampleDumpCommand;
import com.mineutze.worldgen.biomesource.ModBiomeSources;
import com.mineutze.worldgen.biomesource.PatchDebugCommand;
import com.mineutze.worldgen.biomesource.RegionIntentDebugCommand;
import com.mineutze.worldgen.biomesource.TerrainClassDebugCommand;
import com.mineutze.worldgen.biomesource.WorldSeedHolder;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import terrablender.api.Regions;

/**
 * Overworld biome placement no longer goes through TerraBlender -- see
 * biomesource.CustomOverworldBiomeSource, registered below via ModBiomeSources.
 * Nether and End still use TerraBlender / EndBiomeRegistry unchanged: only the
 * Overworld half of this mod dropped the TerraBlender dependency (see DESIGN.md Sec 9).
 */
@Mod(WorldgenMod.MOD_ID)
public class WorldgenMod {

    static final String MOD_ID = "mineutze_filter";
    private static final int NETHER_REGION_WEIGHT = 100_000_000;

    public WorldgenMod(IEventBus modEventBus) {
        // Registry contents must exist before dimension loading -- earlier than
        // FMLCommonSetupEvent below -- so DeferredRegister registration happens here,
        // in the mod constructor, not in onCommonSetup.
        ModBiomeSources.BIOME_SOURCES.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(WorldSeedHolder::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(PatchDebugCommand::register);
        NeoForge.EVENT_BUS.addListener(HeightSampleDumpCommand::register);
        NeoForge.EVENT_BUS.addListener(TerrainClassDebugCommand::register);
        NeoForge.EVENT_BUS.addListener(RegionIntentDebugCommand::register);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Regions.register(new FilterNetherRegion(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "nether_filter"),
                NETHER_REGION_WEIGHT
            ));
            FilterEndBiomes.setup();
        });
    }
}
