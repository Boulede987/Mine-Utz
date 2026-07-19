package com.mineutze.worldgen.biomesource;

import com.mineutze.worldgen.biomesource.patch.SurfacePatchGrid;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;

/**
 * /dump_patch_debug <x> <z> -- prints the resolved biome and an estimated patch size at
 * that column, matching this repo's existing /dump_* logging convention (README's
 * "Registry Dumps" section). Verification tool for rollout Milestone 2 (patch size
 * control): pace across a biome border and confirm the reported width falls in that
 * biome's configured [size_min, size_max] range.
 */
public final class PatchDebugCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    // Team Abnormals' Blueprint (cross-mod biome-injection compat library, common in
    // kitchen-sink packs) wraps WHATEVER biome source a dimension configures in its own
    // ModdedBiomeSource, breaking a plain instanceof check even when our source is still
    // running underneath as its private "originalSource" field. No compile-time
    // dependency on Blueprint here on purpose -- this mod should work whether or not
    // Blueprint happens to be installed, so the class is looked up by name and the field
    // read via reflection, both guarded to silently no-op if Blueprint isn't present or
    // its internals ever change shape.
    private static final String BLUEPRINT_MODDED_BIOME_SOURCE_CLASS =
        "com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSource";
    private static final String BLUEPRINT_ORIGINAL_SOURCE_FIELD = "originalSource";

    private PatchDebugCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dump_patch_debug")
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
        BiomeSource rawBiomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        BiomeSource biomeSource = unwrapKnownWrappers(rawBiomeSource);
        if (!(biomeSource instanceof CustomOverworldBiomeSource customBiomeSource)) {
            source.sendFailure(Component.literal(
                "This dimension (" + level.dimension().location() + ") isn't using CustomOverworldBiomeSource -- "
                    + "actual biome source class: " + biomeSource.getClass().getName()
                    + (biomeSource == rawBiomeSource ? "" : " (unwrapped from " + rawBiomeSource.getClass().getName() + ")")
                    + ", generator class: " + level.getChunkSource().getGenerator().getClass().getName()
            ));
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

    /** Unwraps Blueprint's ModdedBiomeSource (see the class-level comment) if present,
     * following its private originalSource field one or more levels deep in case of
     * nested wrappers. Returns the input unchanged if it isn't a recognized wrapper, or
     * if reflection fails for any reason (Blueprint absent, internals changed shape,
     * etc.) -- never throws, this is a debug convenience, not load-bearing logic. */
    private static BiomeSource unwrapKnownWrappers(BiomeSource biomeSource) {
        BiomeSource current = biomeSource;
        while (BLUEPRINT_MODDED_BIOME_SOURCE_CLASS.equals(current.getClass().getName())) {
            try {
                Field field = current.getClass().getDeclaredField(BLUEPRINT_ORIGINAL_SOURCE_FIELD);
                field.setAccessible(true);
                Object inner = field.get(current);
                if (!(inner instanceof BiomeSource innerBiomeSource)) {
                    break;
                }
                current = innerBiomeSource;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[patch-debug] failed to unwrap {} for debug purposes -- reporting the wrapper as-is",
                    BLUEPRINT_MODDED_BIOME_SOURCE_CLASS, e);
                break;
            }
        }
        return current;
    }
}
