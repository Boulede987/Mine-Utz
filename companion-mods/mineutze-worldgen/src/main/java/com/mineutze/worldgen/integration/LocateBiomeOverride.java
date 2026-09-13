package com.mineutze.worldgen.integration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.time.Duration;

/**
 * Overrides vanilla's {@code /locate biome} with a search radius that actually fits this
 * mod's biome scale. Brigadier merges command nodes by name (same literal/argument names
 * as an existing registration replace that node's own executor, not add a duplicate) -
 * the standard way mods override a vanilla command, confirmed against the real decompiled
 * {@code CommandNode.addChild}. Registering under {@code RegisterCommandsEvent} (which
 * fires after vanilla's own {@code LocateCommand.register}) means this REPLACES vanilla's
 * {@code /locate biome} leaf outright.
 *
 * <p><b>Why vanilla's own version doesn't work here:</b> {@code LocateCommand
 * .MAX_BIOME_SEARCH_RADIUS = 6400} (blocks) is hardcoded, tuned for vanilla's own
 * continuous multi-octave-noise biome distribution, where every biome recurs often in
 * small pockets everywhere. This mod's biomes come from a discrete adjacency-graph CSP
 * instead - placement is thematically CLUSTERED (a climate zone persists for a while
 * before a legal bridge lets it transition to another), so a specific biome can have zero
 * legal path within 6400 blocks if the player happens to be deep inside a differently-
 * themed region - real average patches-per-type within that radius measures fine (~10,
 * computed from the real 48-biome catalog's own target-width distribution), but that
 * average hides real per-biome starvation for structurally-constrained biomes (see e.g.
 * the arctic/mountain pocket investigated earlier this session).
 *
 * <p><b>Real, disclosed cost</b>: like vanilla's own version, this runs synchronously on
 * the calling thread - it blocks until found or the radius is exhausted. Worst case (the
 * target biome doesn't exist anywhere within radius, over otherwise still-fresh
 * territory): roughly (radius / ~2500)^2 real CSP solves (each ~50-150ms, same cost class
 * already accepted for {@link RegionCacheHolder}'s own spawn-area pre-generation) - tens
 * of seconds in the genuine worst case, not unbounded, but real. The common case (biome
 * exists somewhere reasonably close, or the swept area is already explored/pre-solved) is
 * far cheaper - the spiral search (nearest-first, same order vanilla uses) returns the
 * moment it finds a match.
 */
public final class LocateBiomeOverride {

    // First guess, not yet measured against real gameplay - ~7.8x vanilla's own 6400,
    // generous enough to escape one themed region while staying finite (a genuinely
    // absent biome fails in bounded time rather than hanging). Tune from real deployment
    // once that data exists, same discipline already established for RegionCacheHolder's
    // own PRE_GENERATE_RADIUS_BLOCKS/MAX_CACHED_CELLS.
    private static final int SEARCH_RADIUS_BLOCKS = 50_000;
    // Matches vanilla's own BIOME_SAMPLE_RESOLUTION_HORIZONTAL - no reason to change
    // sampling density, only the radius bound.
    private static final int HORIZONTAL_STEP_BLOCKS = 32;

    // Mirrors vanilla's own private ERROR_BIOME_NOT_FOUND (same translation key, so the
    // player sees the identical localized message) - can't reference vanilla's own field,
    // it's private to LocateCommand.
    private static final DynamicCommandExceptionType ERROR_BIOME_NOT_FOUND = new DynamicCommandExceptionType(
        name -> Component.translatableEscape("commands.locate.biome.not_found", name)
    );

    private LocateBiomeOverride() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("locate")
                .then(Commands.literal("biome")
                    .then(Commands.argument("biome", ResourceOrTagArgument.resourceOrTag(event.getBuildContext(), Registries.BIOME))
                        .executes(context -> locateBiome(
                            context.getSource(), ResourceOrTagArgument.getResourceOrTag(context, "biome", Registries.BIOME)
                        ))))
        );
    }

    private static int locateBiome(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> biomeArg) throws CommandSyntaxException {
        BlockPos origin = BlockPos.containing(source.getPosition());
        ServerLevel level = source.getLevel();

        long startNanos = System.nanoTime();
        Pair<BlockPos, Holder<Biome>> found = findClosestBiome(level, origin, biomeArg);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        if (found == null) {
            throw ERROR_BIOME_NOT_FOUND.create(biomeArg.asPrintable());
        }
        return LocateCommand.showLocateResult(source, biomeArg, origin, found, "commands.locate.biome.success", true, elapsed);
    }

    /**
     * Same spiral-search shape as vanilla's own {@code BiomeSource.findClosestBiome3d}
     * (nearest-first, via {@link BlockPos#spiralAround}) but with {@link
     * #SEARCH_RADIUS_BLOCKS} instead of vanilla's hardcoded 6400 - see this class's own
     * doc for why. Purely 2D: this mod's biome placement never varies with Y
     * ({@code ModOverworldBiomeSource.getNoiseBiome} ignores quartY entirely - flat
     * terrain, one biome per column), so no vertical sampling loop is needed at all,
     * unlike vanilla's own version.
     */
    private static Pair<BlockPos, Holder<Biome>> findClosestBiome(ServerLevel level, BlockPos origin, ResourceOrTagArgument.Result<Biome> biomeArg) {
        int radiusSteps = SEARCH_RADIUS_BLOCKS / HORIZONTAL_STEP_BLOCKS;
        for (BlockPos.MutableBlockPos step : BlockPos.spiralAround(BlockPos.ZERO, radiusSteps, Direction.EAST, Direction.SOUTH)) {
            int x = origin.getX() + step.getX() * HORIZONTAL_STEP_BLOCKS;
            int z = origin.getZ() + step.getZ() * HORIZONTAL_STEP_BLOCKS;
            BlockPos pos = new BlockPos(x, origin.getY(), z);
            Holder<Biome> holder = level.getBiome(pos);
            if (biomeArg.test(holder)) {
                return Pair.of(pos, holder);
            }
        }
        return null;
    }
}
