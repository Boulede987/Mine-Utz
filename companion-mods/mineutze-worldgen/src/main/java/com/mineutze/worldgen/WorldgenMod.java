package com.mineutze.worldgen;

import com.mineutze.worldgen.integration.ModBiomeSources;
import com.mineutze.worldgen.integration.RegionCacheHolder;
import com.mineutze.worldgen.integration.RegionDebugCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(WorldgenMod.MOD_ID)
public class WorldgenMod {

    public static final String MOD_ID = "mineutze_worldgen";

    public WorldgenMod(IEventBus modEventBus) {
        // Density function types get wired up here too, once they exist. See doc/ARCHITECTURE.md.
        modEventBus.addListener(ModBiomeSources::register);
        NeoForge.EVENT_BUS.addListener(RegionCacheHolder::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(RegionDebugCommand::register);
    }
}
