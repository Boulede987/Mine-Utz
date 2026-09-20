package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.plan.WorldBiomePlan;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * Operator commands for looking at the world's biome plan (2026-09-20).
 *
 * <p>{@code /mineutzeworldgen plan} says what the plan holds for the position the command runs
 * at, next to what the game itself reports there through {@code level.getBiome} - the two must
 * agree, and if they ever do not, that difference is the bug - plus how the plan was made.
 *
 * <p>{@code /mineutzeworldgen exportbiomes [radiusBlocks] [stepBlocks]} draws the biomes the game
 * REALLY generates around the position (see {@link WorldBiomeExporter}). It runs on the server thread, so
 * keep an export under a few tens of seconds: past 60 s the vanilla watchdog stops the server.
 */
public final class PlanDebugCommand {

    private static final int SERVER_OPERATOR_PERMISSION_LEVEL = 2;
    private static final int DEFAULT_RADIUS_BLOCKS = 2_000;
    private static final int MAX_RADIUS_BLOCKS = 30_000;
    private static final int DEFAULT_STEP_BLOCKS = 16;
    private static final int MAX_STEP_BLOCKS = 256;

    private PlanDebugCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("mineutzeworldgen")
                .requires(source -> source.hasPermission(SERVER_OPERATOR_PERMISSION_LEVEL))
                .then(Commands.literal("plan").executes(PlanDebugCommand::describePlanAtSource))
                .then(Commands.literal("exportbiomes")
                    .executes(context -> exportBiomes(context, DEFAULT_RADIUS_BLOCKS, DEFAULT_STEP_BLOCKS))
                    .then(Commands.argument("radiusBlocks", IntegerArgumentType.integer(64, MAX_RADIUS_BLOCKS))
                        .executes(context -> exportBiomes(context, IntegerArgumentType.getInteger(context, "radiusBlocks"), DEFAULT_STEP_BLOCKS))
                        .then(Commands.argument("stepBlocks", IntegerArgumentType.integer(1, MAX_STEP_BLOCKS))
                            .executes(context -> exportBiomes(
                                context, IntegerArgumentType.getInteger(context, "radiusBlocks"), IntegerArgumentType.getInteger(context, "stepBlocks")
                            )))))
        );
    }

    private static int describePlanAtSource(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Vec3 position = source.getPosition();
        WorldBiomePlan plan = WorldBiomePlanHolder.get();
        BlockPos block = BlockPos.containing(position);

        String planSays = plan.biomeAt(block.getX() + 0.5, block.getZ() + 0.5).toString();
        String gameSays = source.getLevel().getBiome(block).unwrapKey().map(key -> key.location().toString()).orElse("<unregistered>");

        source.sendSuccess(() -> Component.literal(
            "At " + block.getX() + "," + block.getZ() + ": plan says " + planSays + ", the game reports " + gameSays + (planSays.equals(gameSays) ? " (agree)" : " (DISAGREE)")
        ), false);
        source.sendSuccess(() -> Component.literal(String.format(
            Locale.ROOT, "Plan: seed %d, warp strength %.0f, pattern attempt %d, %d strengths tried, tile %.0f blocks across, catalog %s",
            plan.worldSeed(), plan.warpStrength(), plan.patternAttempts(), plan.strengthsTried(), plan.tileSideBlocks(), plan.catalogFingerprint().substring(0, 12)
        )), false);
        return 1;
    }

    private static int exportBiomes(CommandContext<CommandSourceStack> context, int radiusBlocks, int stepBlocks) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        Vec3 position = source.getPosition();

        source.sendSuccess(() -> Component.literal(
            "Sampling the biomes the game generates within " + radiusBlocks + " blocks, every " + stepBlocks + " blocks - this may generate chunks, so it can take a moment..."
        ), false);

        WorldBiomeExporter.Result result = WorldBiomeExporter.exportAroundPosition(level, position, radiusBlocks, stepBlocks);

        source.sendSuccess(() -> Component.literal("Wrote " + result.samples() + " samples to " + result.imageFile() + " (legend: " + result.legendFile() + ")"), false);
        source.sendSuccess(() -> Component.literal(
            "Blended lookup (level.getBiome): " + result.differentFromOwnQuart() + " of " + result.samples() + " samples differ from the plan at their own quart (vanilla's border blending), of which "
                + result.unexplainedDifferences() + " are not explained by a neighboring quart" + (result.unexplainedDifferences() == 0 ? " - OK" : " - BUG")
        ), false);
        source.sendSuccess(() -> Component.literal(
            result.storedBiomeChecks() == 0 ? "Stored chunk biomes: not checked (export too large)"
                : "Stored chunk biomes: " + result.storedBiomeMismatches() + " mismatches with the plan in " + result.storedBiomeChecks() + " checks" + (result.storedBiomeMismatches() == 0 ? " - OK" : " - BUG")
        ), false);
        return result.samples();
    }
}
