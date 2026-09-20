package com.mineutze.worldgen;

import com.mineutze.worldgen.integration.LocateBiomeOverride;
import com.mineutze.worldgen.integration.ModBiomeSources;
import com.mineutze.worldgen.integration.PlanDebugCommand;
import com.mineutze.worldgen.integration.WorldBiomePlanHolder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(WorldgenMod.MOD_ID)
public class WorldgenMod {

    public static final String MOD_ID = "mineutze_worldgen";

    public WorldgenMod(IEventBus modEventBus) {
        modEventBus.addListener(ModBiomeSources::register);
        NeoForge.EVENT_BUS.addListener(WorldBiomePlanHolder::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(PlanDebugCommand::register);
        NeoForge.EVENT_BUS.addListener(LocateBiomeOverride::register);
    }
}
