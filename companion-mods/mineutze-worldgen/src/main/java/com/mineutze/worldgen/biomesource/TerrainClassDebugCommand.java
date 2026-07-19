package com.mineutze.worldgen.biomesource;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /dump_terrain_class <x> <z> -- scans a line of real TerrainClassifier output starting
 * at (x, z), sampled every 4 blocks (matching classifyAtBlock's own quart granularity)
 * for SCAN_LENGTH_BLOCKS. Diagnostic for exactly one question: is real terrain
 * classification itself smooth (long runs of the same class -- the assumption
 * germination's real-terrain-anchoring relies on) or noisy/high-frequency (which would
 * explain fragmented biome placement even with correct placement logic, since
 * TerrainClassifier's thresholds are still uncalibrated placeholder guesses -- see its
 * own class doc and DESIGN.md Sec 11 / the handoff notes' "never been run against real
 * generated terrain"). One-letter-per-sample output makes long runs vs. flicker visible
 * at a glance without needing to fly the actual terrain.
 */
public final class TerrainClassDebugCommand {

    private static final int REQUIRED_PERMISSION_LEVEL = 2;
    private static final int SCAN_LENGTH_BLOCKS = 256;
    private static final int SAMPLE_SPACING_BLOCKS = 4;

    private TerrainClassDebugCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dump_terrain_class")
            .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(context -> {
                    BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
                    return run(context.getSource(), pos.getX(), pos.getZ());
                })
            )
        );
    }

    private static int run(CommandSourceStack source, int blockX, int blockZ) {
        ServerLevel level = source.getLevel();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();

        StringBuilder xLine = new StringBuilder();
        for (int offset = 0; offset < SCAN_LENGTH_BLOCKS; offset += SAMPLE_SPACING_BLOCKS) {
            xLine.append(letterFor(TerrainClassifier.classifyAtBlock(sampler, blockX + offset, blockZ)));
        }
        StringBuilder zLine = new StringBuilder();
        for (int offset = 0; offset < SCAN_LENGTH_BLOCKS; offset += SAMPLE_SPACING_BLOCKS) {
            zLine.append(letterFor(TerrainClassifier.classifyAtBlock(sampler, blockX, blockZ + offset)));
        }

        Climate.TargetPoint origin = sampler.sample(blockX >> 2, 0, blockZ >> 2);
        source.sendSuccess(() -> Component.literal(String.format(
            "(%d, %d) real climate: continentalness=%.3f erosion=%.3f weirdness=%.3f depth=%.3f -- classified %s%n"
                + "+X line (every %d blocks, %d blocks total): %s%n"
                + "+Z line (every %d blocks, %d blocks total): %s%n"
                + "(O=OCEAN C=COAST F=FLAT_LAND H=HILLY M=MOUNTAINOUS R=RIVER -- long runs = smooth real terrain "
                + "classification; frequent flicker = TerrainClassifier's uncalibrated thresholds are noisy here, "
                + "not a placement bug)",
            blockX, blockZ,
            Climate.unquantizeCoord(origin.continentalness()), Climate.unquantizeCoord(origin.erosion()),
            Climate.unquantizeCoord(origin.weirdness()), Climate.unquantizeCoord(origin.depth()),
            TerrainClassifier.classifyAtBlock(sampler, blockX, blockZ),
            SAMPLE_SPACING_BLOCKS, SCAN_LENGTH_BLOCKS, xLine,
            SAMPLE_SPACING_BLOCKS, SCAN_LENGTH_BLOCKS, zLine
        )), false);
        return 1;
    }

    private static char letterFor(TerrainClass terrainClass) {
        return switch (terrainClass) {
            case OCEAN -> 'O';
            case COAST -> 'C';
            case FLAT_LAND -> 'F';
            case HILLY -> 'H';
            case MOUNTAINOUS -> 'M';
            case RIVER -> 'R';
        };
    }
}
