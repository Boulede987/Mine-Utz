package com.mineutze.filtermod;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import terrablender.api.Regions;

@Mod("mineutze_filter")
public class FilterMod {

    public FilterMod(IEventBus modEventBus) {
        modEventBus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Regions.register(new FilterRegion(
                ResourceLocation.fromNamespaceAndPath("mineutze_filter", "overworld_filter"),
                100_000_000
            ));
            Regions.register(new FilterNetherRegion(
                ResourceLocation.fromNamespaceAndPath("mineutze_filter", "nether_filter"),
                100_000_000
            ));
        });
    }
}
