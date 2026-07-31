package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Map;
import java.util.TreeMap;

/** `/mineutzeworldgen solveregion` - solves (or reads the cached solve of) the region at the command source's position and reports it. */
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
        ServerLevel level = source.getLevel();
        Vec3 position = source.getPosition();

        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        MacroCellPos cell = new MacroCellPos(
            Math.floorDiv((int) position.x, grid.macroCellSizeBlocks()),
            Math.floorDiv((int) position.z, grid.macroCellSizeBlocks())
        );
        RegionPos region = grid.regionOf(cell);

        Map<MacroCellPos, BiomeId> assignment = RegionCacheManager.getOrSolve(level, region);
        Map<BiomeId, Long> biomeCounts = countByBiome(assignment);

        source.sendSuccess(() -> Component.literal(
            "Solved region " + region.x() + "," + region.z() + " (" + assignment.size() + " cells): " + biomeCounts
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
