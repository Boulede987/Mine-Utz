package com.mineutze.worldgen;

import com.mineutze.worldgen.integration.ModBiomeSources;
import com.mineutze.worldgen.integration.ModDensityFunctionTypes;
import com.mineutze.worldgen.integration.RegionCacheHolder;
import com.mineutze.worldgen.integration.RegionDebugCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(WorldgenMod.MOD_ID)
public class WorldgenMod {

    public static final String MOD_ID = "mineutze_worldgen";

    public WorldgenMod(IEventBus modEventBus) {
        modEventBus.addListener(ModBiomeSources::register);
        modEventBus.addListener(ModDensityFunctionTypes::register);
        NeoForge.EVENT_BUS.addListener(RegionCacheHolder::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(RegionDebugCommand::register);
    }
}
