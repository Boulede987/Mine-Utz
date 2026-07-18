package com.mineutze.worldgen.biomesource;

import com.mineutze.worldgen.biomesource.patch.SurfacePatchGrid;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /dump_patch_debug <x> <z> -- prints the resolved biome and an estimated patch size at
 * that column, matching this repo's existing /dump_* logging convention (README's
 * "Registry Dumps" section). Verification tool for rollout Milestone 2 (patch size
 * control): pace across a biome border and confirm the reported width falls in that
 * biome's configured [size_min, size_max] range.
 */
public final class PatchDebugCommand {

    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private PatchDebugCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dump_patch_debug")
            .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
            .then(Commands.argument("x", IntegerArgumentType.integer())
                .then(Commands.argument("z", IntegerArgumentType.integer())
                    .executes(context -> run(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "x"),
                        IntegerArgumentType.getInteger(context, "z")
                    ))
                )
            )
        );
    }

    private static int run(CommandSourceStack source, int blockX, int blockZ) {
        ServerLevel level = source.getLevel();
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        if (!(biomeSource instanceof CustomOverworldBiomeSource customBiomeSource)) {
            source.sendFailure(Component.literal("This dimension isn't using CustomOverworldBiomeSource."));
            return 0;
        }

        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        SurfacePatchGrid.PatchMeasurement measurement = customBiomeSource.debugMeasurePatch(blockX, blockZ, sampler);
        if (measurement == null) {
            source.sendSuccess(() -> Component.literal(
                "(" + blockX + ", " + blockZ + "): no configured biome covers this column (fallback)."
            ), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal(String.format(
            "(%d, %d): %s -- ~%d cached cells, ~%.0f block radius (%.0f block diameter)",
            blockX, blockZ, measurement.biome().location(),
            measurement.cachedCellCount(), measurement.approxRadiusBlocks(), measurement.approxRadiusBlocks() * 2
        )), false);
        return 1;
    }
}
