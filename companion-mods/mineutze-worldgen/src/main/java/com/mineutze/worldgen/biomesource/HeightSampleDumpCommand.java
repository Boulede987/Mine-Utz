package com.mineutze.worldgen.biomesource;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * CORRECTNESS NOTE (found via real captured data returning -64, the world-bottom
 * sentinel, for 97% of samples over unexplored terrain): Level.getHeight does NOT force
 * chunk generation on its own -- verified directly against decompiled vanilla source
 * (Level.java's real getHeight implementation checks hasChunk(...) first and silently
 * returns getMinY() if the chunk isn't already loaded, never triggering generation).
 * forceRealHeight below explicitly forces the chunk via getChunk(cx, cz) (confirmed,
 * separately, to force synchronous full generation) before every height read, fixing
 * this. Consequence: running this over a large UNEXPLORED radius is now genuinely
 * expensive (real chunk generation, not a cheap heightmap read) -- the sample cap below
 * is the real safety valve for that cost now, not a redundant one.
 *
 * /dump_height_samples walk [radius] [step] -- same dump, then teleports the sender
 * forward (their current horizontal look direction, pitch ignored) by 2*radius blocks,
 * landing on the real surface height at the destination -- covers varied terrain by
 * repeated invocation (spam the command / bind it) without manually re-flying between
 * every capture. 2*radius keeps consecutive sample areas adjacent, not overlapping.
 * Direction is locked to whichever way the sender was facing at THAT invocation, not
 * re-read from a fixed heading -- turn before the next call to redirect the sweep.
 */
public final class HeightSampleDumpCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int MAX_TOTAL_SAMPLES = 10_000;
    private static final int DEFAULT_RADIUS_BLOCKS = 256;
    private static final int DEFAULT_STEP_BLOCKS = 8;

    // Landed above the real surface height, not exactly on it -- avoids spawning the
    // player inside terrain on a destination column with an overhang or a sudden rise
    // relative to its neighbors.
    private static final int WALK_LANDING_CLEARANCE_BLOCKS = 2;

    private HeightSampleDumpCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dump_height_samples")
            .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
            .executes(context -> run(context.getSource(), DEFAULT_RADIUS_BLOCKS, DEFAULT_STEP_BLOCKS, false))
            .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                .then(Commands.argument("step", IntegerArgumentType.integer(1))
                    .executes(context -> run(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius"),
                        IntegerArgumentType.getInteger(context, "step"),
                        false
                    ))
                )
            )
            .then(Commands.literal("walk")
                .executes(context -> run(context.getSource(), DEFAULT_RADIUS_BLOCKS, DEFAULT_STEP_BLOCKS, true))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                    .then(Commands.argument("step", IntegerArgumentType.integer(1))
                        .executes(context -> run(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "radius"),
                            IntegerArgumentType.getInteger(context, "step"),
                            true
                        ))
                    )
                )
            )
        );
    }

    private static int run(CommandSourceStack source, int radiusBlocks, int stepBlocks, boolean walkAfter) throws CommandSyntaxException {
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
                int height = forceRealHeight(level, x, z);
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
        if (walkAfter) {
            BlockPos destination = teleportForward(source, level, center, radiusBlocks);
            source.sendSuccess(() -> Component.literal(
                "Dumped " + finalSampleCount + " height samples to the log. Walked to ("
                    + destination.getX() + ", " + destination.getZ() + ")."
            ), false);
        } else {
            source.sendSuccess(() -> Component.literal("Dumped " + finalSampleCount + " height samples to the log."), false);
        }
        return sampleCount;
    }

    /** Moves the sender 2*radiusBlocks forward along their current horizontal look
     * direction (yaw only -- pitch ignored, so looking up/down doesn't send them into
     * the sky or underground), landing WALK_LANDING_CLEARANCE_BLOCKS above the real
     * surface height at the destination column. 2*radiusBlocks keeps this capture's
     * sampled square and the next one adjacent rather than overlapping. */
    private static BlockPos teleportForward(
        CommandSourceStack source, ServerLevel level, BlockPos from, int radiusBlocks
    ) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        double yawRadians = Math.toRadians(player.getYRot());
        // Minecraft yaw: 0 = facing south (+Z), increases clockwise when viewed from above.
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        int travelBlocks = radiusBlocks * 2;

        int destX = from.getX() + (int) Math.round(forwardX * travelBlocks);
        int destZ = from.getZ() + (int) Math.round(forwardZ * travelBlocks);
        int destY = forceRealHeight(level, destX, destZ) + WALK_LANDING_CLEARANCE_BLOCKS;

        player.teleportTo(destX + 0.5, destY, destZ + 0.5);
        return new BlockPos(destX, destY, destZ);
    }

    /** Level.getHeight alone silently returns getMinY() (world bottom) for a chunk
     * that isn't already loaded -- it does NOT force generation, despite what this
     * class used to assume (see the class-level correctness note). Forcing the chunk
     * via getChunk(cx, cz) first (confirmed: LevelReader's 2-arg getChunk defaults to
     * requireChunk=true, which synchronously generates to FULL status) makes hasChunk
     * true before getHeight ever checks it, so the real generated height comes back. */
    private static int forceRealHeight(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }
}
