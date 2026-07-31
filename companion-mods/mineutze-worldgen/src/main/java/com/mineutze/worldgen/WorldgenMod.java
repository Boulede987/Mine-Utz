package com.mineutze.worldgen;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(WorldgenMod.MOD_ID)
public class WorldgenMod {

    public static final String MOD_ID = "mineutze_worldgen";

    public WorldgenMod(IEventBus modEventBus) {
        // Registries (biome graph config, density function types, etc.) get
        // wired up here as they're implemented. See doc/ARCHITECTURE.md.
    }
}
