package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.plan.BlockPosition;
import com.mineutze.worldgen.plan.WorldBiomePlan;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.time.Duration;
import java.util.Optional;

/**
 * Replaces vanilla's {@code /locate biome} with an EXACT search of the world's {@link WorldBiomePlan}
 * (2026-09-20). Vanilla probes the world in a growing spiral and gives up at a hardcoded 6400 blocks,
 * because in vanilla a biome can genuinely be absent nearby. Here the world is a periodic tile that
 * contains every catalog biome, so the plan can simply say where the nearest one is - to the block,
 * immediately (a few milliseconds per biome), and never "not found" for a biome the catalog has.
 *
 * <p>Brigadier merges command nodes by name (same literal/argument names as an existing registration
 * replace that node's own executor, not add a duplicate) - the standard way mods override a vanilla
 * command. Registering under {@code RegisterCommandsEvent}, which fires after vanilla's own
 * {@code LocateCommand.register}, replaces vanilla's {@code /locate biome} leaf outright.
 *
 * <p>The argument may be one biome or a biome TAG; every catalog biome the argument accepts is
 * searched and the closest hit wins. "Not found" now only means the argument names no biome of this
 * world's catalog.
 */
public final class LocateBiomeOverride {

    // Mirrors vanilla's own private ERROR_BIOME_NOT_FOUND (same translation key, so the player sees the
    // identical localized message) - can't reference vanilla's own field, it's private to LocateCommand.
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
     * Of every catalog biome the argument accepts, the one whose nearest occurrence in the plan is
     * closest to {@code origin}. The position is horizontal only: this mod's biomes are flat, so it
     * keeps the player's own height.
     */
    private static Pair<BlockPos, Holder<Biome>> findClosestBiome(ServerLevel level, BlockPos origin, ResourceOrTagArgument.Result<Biome> biomeArg) {
        WorldBiomePlan plan = WorldBiomePlanHolder.get();
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        BestBiome best = new BestBiome(null, Double.MAX_VALUE);
        for (BiomeId biomeId : plan.biomes()) {
            best = processCandidate(registry, origin, biomeArg, plan, biomeId, best);
        }
        return best.pair();
    }

    private record BestBiome(Pair<BlockPos, Holder<Biome>> pair, double squaredDistance) {
    }

    private static BestBiome processCandidate(
        Registry<Biome> registry, BlockPos origin, ResourceOrTagArgument.Result<Biome> biomeArg, WorldBiomePlan plan, BiomeId biomeId, BestBiome best
    ) {
        Optional<Holder.Reference<Biome>> holder = registry.getHolder(ResourceKey.create(Registries.BIOME, BiomeIdAdapter.toResourceLocation(biomeId)));
        if (holder.isEmpty() || !biomeArg.test(holder.get())) {
            return best;
        }
        Optional<BlockPosition> nearest = plan.nearestPositionOf(biomeId, origin.getX(), origin.getZ());
        if (nearest.isEmpty()) {
            return best;
        }
        double deltaX = nearest.get().x() - origin.getX();
        double deltaZ = nearest.get().z() - origin.getZ();
        double squaredDistance = deltaX * deltaX + deltaZ * deltaZ;
        if (squaredDistance < best.squaredDistance()) {
            return new BestBiome(Pair.of(new BlockPos(nearest.get().x(), origin.getY(), nearest.get().z()), holder.get()), squaredDistance);
        }
        return best;
    }
}
