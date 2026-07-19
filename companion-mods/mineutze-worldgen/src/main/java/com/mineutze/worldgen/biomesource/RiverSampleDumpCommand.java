package com.mineutze.worldgen.biomesource;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * /dump_river_samples [radius] [step] -- bulk-scans a grid the same way
 * /dump_height_samples does (same force-generation fix -- see that class's own
 * correctness note, this reuses the identical getChunk-before-getHeight pattern), but
 * only LOGS a column if its real surface block is water. Ground truth, not inference:
 * RIVER_WEIRDNESS_ABS_HI calibration via the depression proxy in
 * calibrate_terrain_classes.py kept failing its own sanity check even after a session
 * spent manually flying along visible rivers -- partly because our own RIVER biome
 * placement (which is what a player would otherwise use to "find a river") is itself
 * gated by the very threshold being calibrated, and real carved river channels
 * confirmed NOT reliably labeled RIVER biome by this modpack today. This sidesteps both
 * problems: it finds real water computationally, independent of any biome label, and
 * records the exact climate values AT that confirmed-water column directly -- no
 * roughness/depression proxy, no manual searching.
 *
 * Output volume self-limits (most scanned columns aren't water and get discarded, not
 * logged) but the EXPENSIVE part -- forcing generation for every candidate column -- is
 * identical cost to /dump_height_samples regardless of the outcome, so the same sample
 * cap applies for the same reason.
 */
public final class RiverSampleDumpCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int MAX_TOTAL_SAMPLES = 10_000;
    private static final int DEFAULT_RADIUS_BLOCKS = 256;
    private static final int DEFAULT_STEP_BLOCKS = 8;

    private RiverSampleDumpCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dump_river_samples")
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
        output.append("===== DUMP START (river_samples) =====\n");
        output.append("x,z,continentalness,erosion,weirdness,height\n");
        int scannedCount = 0;
        int waterCount = 0;
        for (int x = center.getX() - radiusBlocks; x <= center.getX() + radiusBlocks; x += stepBlocks) {
            for (int z = center.getZ() - radiusBlocks; z <= center.getZ() + radiusBlocks; z += stepBlocks) {
                level.getChunk(x >> 4, z >> 4); // force real generation -- see class doc
                int height = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                // WORLD_SURFACE reports the Y one above the highest non-air block, so the
                // actual surface block (water, if this column is a river/lake/ocean) sits
                // one below that.
                BlockPos surfacePos = new BlockPos(x, height - 1, z);
                scannedCount++;
                if (!level.getFluidState(surfacePos).is(FluidTags.WATER)) {
                    continue;
                }
                waterCount++;
                Climate.TargetPoint sample = sampler.sample(x >> 2, 0, z >> 2);
                output.append(x).append(',').append(z).append(',')
                    .append(Climate.unquantizeCoord(sample.continentalness())).append(',')
                    .append(Climate.unquantizeCoord(sample.erosion())).append(',')
                    .append(Climate.unquantizeCoord(sample.weirdness())).append(',')
                    .append(height).append('\n');
            }
        }
        output.append("===== DUMP END (river_samples) =====");
        LOGGER.info(output.toString());

        int finalScannedCount = scannedCount;
        int finalWaterCount = waterCount;
        source.sendSuccess(() -> Component.literal(
            "Scanned " + finalScannedCount + " columns, found " + finalWaterCount + " confirmed water -- logged."
        ), false);
        return waterCount;
    }
}
