package com.mineutze.worldgen;

import com.mineutze.worldgen.integration.RegionDebugCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(WorldgenMod.MOD_ID)
public class WorldgenMod {

    public static final String MOD_ID = "mineutze_worldgen";

    public WorldgenMod(IEventBus modEventBus) {
        // Registries (density function types, etc.) get wired up here as they're
        // implemented. See doc/ARCHITECTURE.md.
        NeoForge.EVENT_BUS.addListener(RegionDebugCommand::register);
    }
}
