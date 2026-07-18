package com.mineutze.worldgen.biomesource;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * /dump_height_samples [radius] [step] -- dumps (continentalness, erosion, weirdness,
 * real generated height) tuples around the sender to logs/latest.log, matching this
 * repo's existing "===== DUMP START =====" convention (README's Registry Dumps
 * section) so the same copy-out-of-the-log workflow applies.
 *
 * This is the data source for rollout Milestone 3b: TerrainClassifier's erosion/weirdness
 * thresholds are currently placeholder guesses (see its class comment) -- this modpack's
 * real noise router, once BoP/BWG etc. are layered in, is not guaranteed to match
 * vanilla's own breakpoints. Run this over varied, already-explored terrain (flat, hilly,
 * and mountainous areas), then feed the log output to
 * pythonScripts/worldgen/calibrate_terrain_classes.py to get real threshold suggestions.
 *
 * Deliberately only walks already-loaded/explored terrain in practice -- getHeight on
 * fresh chunks forces generation, so running this over a huge unexplored radius would
 * itself be slow for the same reason the surface tier's window resolution is. The sample
 * cap exists to keep a single invocation cheap regardless.
 */
public final class HeightSampleDumpCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int MAX_TOTAL_SAMPLES = 10_000;
    private static final int DEFAULT_RADIUS_BLOCKS = 256;
    private static final int DEFAULT_STEP_BLOCKS = 8;

    private HeightSampleDumpCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dump_height_samples")
            .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
            .executes(context -> run(context.getSource(), DEFAULT_RADIUS_BLOCKS, DEFAULT_STEP_BLOCKS))
            .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                .then(Commands.argument("step", IntegerArgumentType.integer(1))
                    .executes(context -> run(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius"),
                        IntegerArgumentType.getInteger(context, "step")
                    ))
                )
            )
        );
    }

    private static int run(CommandSourceStack source, int radiusBlocks, int stepBlocks) {
        long sideSamples = (2L * radiusBlocks / stepBlocks) + 1;
        long totalSamples = sideSamples * sideSamples;
        if (totalSamples > MAX_TOTAL_SAMPLES) {
            source.sendFailure(Component.literal(
                "That would be " + totalSamples + " samples (max " + MAX_TOTAL_SAMPLES + ") -- raise step or shrink radius."
            ));
            return 0;
        }

        ServerLevel level = source.getLevel();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        BlockPos center = BlockPos.containing(source.getPosition());

        StringBuilder output = new StringBuilder();
        output.append("===== DUMP START (height_samples) =====\n");
        output.append("x,z,continentalness,erosion,weirdness,height\n");
        int sampleCount = 0;
        for (int x = center.getX() - radiusBlocks; x <= center.getX() + radiusBlocks; x += stepBlocks) {
            for (int z = center.getZ() - radiusBlocks; z <= center.getZ() + radiusBlocks; z += stepBlocks) {
                Climate.TargetPoint sample = sampler.sample(x >> 2, 0, z >> 2);
                int height = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                output.append(x).append(',').append(z).append(',')
                    .append(Climate.unquantizeCoord(sample.continentalness())).append(',')
                    .append(Climate.unquantizeCoord(sample.erosion())).append(',')
                    .append(Climate.unquantizeCoord(sample.weirdness())).append(',')
                    .append(height).append('\n');
                sampleCount++;
            }
        }
        output.append("===== DUMP END (height_samples) =====");
        LOGGER.info(output.toString());

        int finalSampleCount = sampleCount;
        source.sendSuccess(() -> Component.literal("Dumped " + finalSampleCount + " height samples to the log."), false);
        return sampleCount;
    }
}
