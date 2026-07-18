package com.mineutze.worldgen.biomesource;

import com.mineutze.worldgen.biomesource.patch.SurfacePatchGrid;
import com.mineutze.worldgen.biomesource.patch.UndergroundPatchGrid;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * Overworld BiomeSource replacing TerraBlender's FilterRegion. Dispatches each queried
 * column to the surface patch-growth tier (SurfacePatchGrid) or the underground tier
 * (UndergroundPatchGrid, requirement 3's exact 1:1 surface-to-cave-biome linkage).
 *
 * Fallback (used both for underground columns with no linked cave biome, and surface
 * columns no configured biome's terrain_classes cover): resolves to whatever biome the
 * surface tier assigns for that column, i.e. an unlinked cave defaults to "the same
 * biome as what's directly above it" rather than vanilla's own dripstone/lush/deep-dark
 * placement rules. Deliberate scope cut, not an oversight -- the plan's original design
 * called for wrapping vanilla's own default MultiNoiseBiomeSource here, but that needs a
 * second registry lookup (Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST) threaded
 * through the codec on top of the Biome one already here, for a payoff (matching
 * vanilla's exact cave-biome variety) requirement 3 never actually asked for -- it asked
 * for specific cave biomes under specific surface biomes, which this delivers. Losing
 * vanilla's dripstone/lush/deep-dark variety everywhere NOT explicitly linked is a real,
 * known limitation of this choice.
 *
 * See DESIGN.md and the implementation plan for the full design: patch size
 * (Milestone 2), real terrain-class gating (Milestone 3), soft adjacency (Milestone 4).
 */
public final class CustomOverworldBiomeSource extends BiomeSource {

    public static final MapCodec<CustomOverworldBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            RegistryOps.retrieveGetter(Registries.BIOME)
        ).apply(instance, CustomOverworldBiomeSource::new)
    );

    // Matches biome_loader.py / extract_noise_to_json.py's surface/underground depth
    // section boundary -- keep these two constant sets in lockstep (see the plan's
    // "Dispatch" section).
    private static final double SURFACE_DEPTH_MAX = 0.05;

    private final HolderGetter<Biome> biomeLookup;
    private volatile SurfacePatchGrid surfacePatchGrid;
    private volatile UndergroundPatchGrid undergroundPatchGrid;

    public CustomOverworldBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return BiomeConfigRegistry.allConfigs().stream()
            .map(BiomeConfig::id)
            .map(biomeLookup::getOrThrow);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        int blockX = quartX << 2;
        int blockZ = quartZ << 2;
        Climate.TargetPoint targetPoint = sampler.sample(quartX, quartY, quartZ);
        double depth = Climate.unquantizeCoord(targetPoint.depth());

        ResourceKey<Biome> resolved = depth <= SURFACE_DEPTH_MAX
            ? surfacePatchGrid().resolve(blockX, blockZ, sampler)
            : undergroundPatchGrid().resolve(blockX, blockZ, sampler);
        if (resolved != null) {
            return biomeLookup.getOrThrow(resolved);
        }

        // Fallback for (a) an underground column with no linked cave biome and (b) a
        // surface column no configured biome's terrain_classes cover -- see class
        // comment for why "same as the surface biome above" instead of a real vanilla
        // MultiNoiseBiomeSource wrap. Only reachable itself in the rare double-gap case
        // (surface tier ALSO has nothing here) does this fall through to the crude
        // first-configured-biome answer.
        ResourceKey<Biome> surfaceFallback = surfacePatchGrid().resolve(blockX, blockZ, sampler);
        return biomeLookup.getOrThrow(surfaceFallback != null ? surfaceFallback : BiomeConfigRegistry.allConfigs().get(0).id());
    }

    /** /dump_patch_debug entry point -- see SurfacePatchGrid.measurePatchAt for caveats. */
    public SurfacePatchGrid.PatchMeasurement debugMeasurePatch(int blockX, int blockZ, Climate.Sampler sampler) {
        return surfacePatchGrid().measurePatchAt(blockX, blockZ, sampler);
    }

    private SurfacePatchGrid surfacePatchGrid() {
        SurfacePatchGrid grid = surfacePatchGrid;
        if (grid == null) {
            synchronized (this) {
                grid = surfacePatchGrid;
                if (grid == null) {
                    grid = new SurfacePatchGrid(WorldSeedHolder.overworldSeed(), BiomeConfigRegistry.allConfigs());
                    surfacePatchGrid = grid;
                }
            }
        }
        return grid;
    }

    private UndergroundPatchGrid undergroundPatchGrid() {
        UndergroundPatchGrid grid = undergroundPatchGrid;
        if (grid == null) {
            synchronized (this) {
                grid = undergroundPatchGrid;
                if (grid == null) {
                    grid = new UndergroundPatchGrid(WorldSeedHolder.overworldSeed(), BiomeConfigRegistry.allConfigs(), surfacePatchGrid());
                    undergroundPatchGrid = grid;
                }
            }
        }
        return grid;
    }
}
