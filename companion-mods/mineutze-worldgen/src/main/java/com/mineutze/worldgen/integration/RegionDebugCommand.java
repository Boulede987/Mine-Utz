package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Map;
import java.util.TreeMap;

/**
 * `/mineutzeworldgen solveregion` - reports the region solve at the command source's
 * position, via the SAME {@link RegionCacheHolder}/{@code SeedRegionCache} in-memory
 * path real terrain generation uses ({@link ModOverworldBiomeSource#getNoiseBiome}).
 *
 * Used to go through a separate SavedData-backed cache instead (since deleted:
 * RegionCacheManager/RegionCacheData). That path independently tracked "which
 * neighbor regions are already solved" against SavedData rather than the in-memory
 * cache real terrain generation uses, so it could - and was observed in practice to -
 * report a completely different placement than what the player was actually standing
 * in: whichever regions the player had already walked through were irrelevant to it,
 * since it checked a cache namespace their exploration never touched. A command meant
 * to explain what you're standing in has to use the cache that generated it - not a
 * second implementation of "same algorithm" that happens to track state separately.
 */
public final class RegionDebugCommand {

    private static final int SERVER_OPERATOR_PERMISSION_LEVEL = 2;

    private RegionDebugCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("mineutzeworldgen")
                .requires(source -> source.hasPermission(SERVER_OPERATOR_PERMISSION_LEVEL))
                .then(Commands.literal("solveregion").executes(RegionDebugCommand::solveRegionAtSource))
        );
    }

    private static int solveRegionAtSource(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Vec3 position = source.getPosition();

        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        MacroCellPos cell = new MacroCellPos(
            Math.floorDiv((int) position.x, grid.macroCellSizeBlocks()),
            Math.floorDiv((int) position.z, grid.macroCellSizeBlocks())
        );
        RegionPos region = grid.regionOf(cell);

        RegionSolveResult result = RegionCacheHolder.get().resultFor(region);
        Map<MacroCellPos, BiomeId> assignment = result.assignment();
        Map<BiomeId, Long> biomeCounts = countByBiome(assignment);
        BiomeId here = assignment.get(cell);

        source.sendSuccess(() -> Component.literal(
            "Region " + region.x() + "," + region.z() + " (" + assignment.size() + " cells, live SeedRegionCache): "
                + "here=" + here + " " + biomeCounts
        ), false);
        return assignment.size();
    }

    private static Map<BiomeId, Long> countByBiome(Map<MacroCellPos, BiomeId> assignment) {
        Map<BiomeId, Long> counts = new TreeMap<>();
        for (BiomeId biome : assignment.values()) {
            counts.merge(biome, 1L, Long::sum);
        }
        return counts;
    }
}
