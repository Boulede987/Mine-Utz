package com.mineutze.worldgen.biomesource;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /dump_region_intent <x> <z> -- Phase 2 Stage 2 diagnostic (TERRAIN_COHERENCE_DESIGN.md):
 * prints the resolved biome, its rolled RegionFamily (or "none" if this biome declared
 * no regionFamilyWeights), and whether SurfacePatchGrid's tile cache already had this
 * cell's shape intent resolved BEFORE this command triggered a fresh resolution. That
 * last part is the actual point of this command: Stage 3's density function will read
 * the cache non-destructively (SurfacePatchGrid.peekRegionIntent, no Climate.Sampler
 * available to force a resolution the way this command can) and fall back to real
 * Tectonic terrain on any miss -- this command is how to check, empirically, in a real
 * running world, whether the BIOMES chunk-gen step (which populates this cache) really
 * does run before the density-function evaluation Stage 3 will hook into, rather than
 * trusting that ordering assumption blind.
 */
public final class RegionIntentDebugCommand {

    private static final int REQUIRED_PERMISSION_LEVEL = 2;

    private RegionIntentDebugCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dump_region_intent")
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
        BiomeSource biomeSource = BiomeSourceUnwrapper.unwrapKnownWrappers(rawBiomeSource);
        if (!(biomeSource instanceof CustomOverworldBiomeSource customBiomeSource)) {
            source.sendFailure(Component.literal(
                "This dimension (" + level.dimension().location() + ") isn't using CustomOverworldBiomeSource -- "
                    + "actual biome source class: " + biomeSource.getClass().getName()
                    + (biomeSource == rawBiomeSource ? "" : " (unwrapped from " + rawBiomeSource.getClass().getName() + ")")
            ));
            return 0;
        }

        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        CustomOverworldBiomeSource.RegionIntentDebugInfo info = customBiomeSource.debugRegionIntent(blockX, blockZ, sampler);
        if (info == null) {
            source.sendSuccess(() -> Component.literal(
                "(" + blockX + ", " + blockZ + "): no configured biome covers this column (fallback)."
            ), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal(String.format(
            "(%d, %d): biome=%s regionFamily=%s -- cache %s before this query",
            blockX, blockZ, info.biome().location(),
            info.regionFamily() == null ? "none (stays reactive to real Tectonic terrain)" : info.regionFamily(),
            info.cacheHitBeforeQuery() ? "HIT" : "MISS"
        )), false);
        return 1;
    }
}
