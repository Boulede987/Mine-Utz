package com.mineutze.worldgen.biomesource;

import com.mineutze.worldgen.biomesource.patch.PatchGrowthEngine;
import com.mineutze.worldgen.biomesource.patch.PatchSeedHasher;
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

import java.util.List;
import java.util.stream.Stream;

/**
 * Overworld BiomeSource replacing TerraBlender's FilterRegion. Dispatches each queried
 * column to the surface patch-growth tier (SurfacePatchGrid) or the underground tier
 * (UndergroundPatchGrid, requirement 3's exact 1:1 surface-to-cave-biome linkage).
 *
 * Fallback chain, in order: (1) an underground column with no linked cave biome falls
 * back to "the same biome as the surface directly above it" (see the class comment on
 * why not a real vanilla MultiNoiseBiomeSource wrap -- known, accepted limitation, loses
 * vanilla's dripstone/lush/deep-dark variety everywhere not explicitly linked); (2) a
 * TRUE gap -- no patch's growth ever claimed this cell at all -- falls back to a
 * terrain-class-eligible biome for the REAL terrain at this exact position, picked the
 * same weighted way germination does, NOT an arbitrary default. That second case is a
 * real, if narrow, failure mode: a tiny, isolated terrain feature (e.g. a small
 * mountainous speck) can lose its own germination bid to a nearby, unrelated,
 * higher-priority candidate -- local-maximum suppression isn't restricted to
 * same-terrain-class competitors -- and if no other patch's growth reaches it either, it
 * has to be resolved from scratch right here. Returning "whatever's first in the
 * configured list" for that case (the original Milestone 1 scaffolding) would have
 * silently mislabeled a real mountain speck as plains -- confirmed, not hypothetical.
 */
public final class CustomOverworldBiomeSource extends BiomeSource {

    public static final MapCodec<CustomOverworldBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            RegistryOps.retrieveGetter(Registries.BIOME)
        ).apply(instance, CustomOverworldBiomeSource::new)
    );

    // getNoiseBiome is called at many Y-levels per column -- this threshold decides
    // which ones count as "surface" vs "underground". 0.05 (too close to zero) was a
    // real, confirmed bug: on any not-perfectly-flat terrain, near-surface columns got
    // misrouted to the underground tier, picked up their column's linked cave biome, and
    // showed up as e.g. lush_caves patches sitting visibly on top of a plain. Matches
    // the old FilterRegion.java's underground entries, which used depth [0.2, 0.9] --
    // that precedent is what 0.2 here is restoring, not a fresh guess.
    private static final double SURFACE_DEPTH_MAX = 0.2;

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

        if (depth <= SURFACE_DEPTH_MAX) {
            SurfacePatchGrid.BiomeAndFamily resolved = surfacePatchGrid().resolveWithFamily(blockX, blockZ, sampler);
            if (resolved != null) {
                debugRecord(blockX, blockZ, sampler, resolved.biome(), resolved.regionFamily(), "surface");
                return biomeLookup.getOrThrow(resolved.biome());
            }
        } else {
            ResourceKey<Biome> resolved = undergroundPatchGrid().resolve(blockX, blockZ, sampler);
            if (resolved != null) {
                debugRecord(blockX, blockZ, sampler, resolved, null, "underground");
                return biomeLookup.getOrThrow(resolved);
            }
        }

        // Fallback (1): underground gap with no linked cave biome -- "same as the
        // surface above" (see class comment). For a surface gap this re-queries the
        // exact same thing and comes back null again, falling through to (2).
        SurfacePatchGrid.BiomeAndFamily surfaceFallback = surfacePatchGrid().resolveWithFamily(blockX, blockZ, sampler);
        if (surfaceFallback != null) {
            debugRecord(blockX, blockZ, sampler, surfaceFallback.biome(), surfaceFallback.regionFamily(), "fallback-surface-for-underground-gap");
            return biomeLookup.getOrThrow(surfaceFallback.biome());
        }

        // Fallback (2): a true gap -- pick among whatever's terrain-class-eligible for
        // what's REALLY here, not an arbitrary default (see class comment).
        ResourceKey<Biome> gapFallback = pickTerrainAppropriateFallback(blockX, blockZ, sampler);
        debugRecord(blockX, blockZ, sampler, gapFallback, null, "fallback-terrain-appropriate-gap");
        return biomeLookup.getOrThrow(gapFallback);
    }

    /** WorldgenDebugDump hook -- see that class's own doc. No-op (not even a
     * TerrainClassifier call) when WorldgenDebugDump.ENABLED is false, so this costs
     * nothing in normal operation. */
    private static void debugRecord(
        int blockX, int blockZ, Climate.Sampler sampler, ResourceKey<Biome> biome, RegionFamily regionFamily, String source
    ) {
        if (!WorldgenDebugDump.ENABLED) {
            return;
        }
        TerrainClass terrainClass = TerrainClassifier.classifyAtBlock(sampler, blockX, blockZ);
        WorldgenDebugDump.record(blockX, blockZ, sampler, terrainClass, biome, regionFamily, source);
    }

    private static final int FALLBACK_PICK_HASH_NAMESPACE = 99;

    private ResourceKey<Biome> pickTerrainAppropriateFallback(int blockX, int blockZ, Climate.Sampler sampler) {
        TerrainClass terrainClass = TerrainClassifier.classifyAtBlock(sampler, blockX, blockZ);
        List<BiomeConfig> eligible = BiomeConfigRegistry.allConfigs().stream()
            .filter(biome -> biome.terrainClasses().contains(terrainClass))
            .toList();
        if (eligible.isEmpty()) {
            // No configured biome declares this terrain class at all -- should not
            // happen with the current config (every TerrainClass has at least one owner),
            // but stay correct if that ever stops being true rather than throwing.
            return BiomeConfigRegistry.allConfigs().get(0).id();
        }
        long seed = PatchSeedHasher.cellSeed(WorldSeedHolder.overworldSeed(), blockX, FALLBACK_PICK_HASH_NAMESPACE, blockZ);
        return PatchGrowthEngine.pickWeightedBiome(eligible, seed).id();
    }

    /** /dump_patch_debug entry point -- see SurfacePatchGrid.measurePatchAt for caveats. */
    public SurfacePatchGrid.PatchMeasurement debugMeasurePatch(int blockX, int blockZ, Climate.Sampler sampler) {
        return surfacePatchGrid().measurePatchAt(blockX, blockZ, sampler);
    }

    /** /dump_region_intent entry point. Peeks the cache BEFORE the real resolving call
     * below, so the reported cacheHitBeforeQuery reflects the cache-warm assumption
     * Stage 3's density function will rely on (it has no Climate.Sampler to force a
     * fresh resolution with -- see TERRAIN_COHERENCE_DESIGN.md's "Climate.Sampler access
     * problem" section), not whatever state this debug call itself just caused. A peek
     * miss can mean either "tile not resolved yet" or "resolved, but this biome declared
     * no regionFamilyWeights" -- both read identically to a real consumer, so this
     * command doesn't try to distinguish them either (see SurfacePatchGrid.peekRegionIntent). */
    public RegionIntentDebugInfo debugRegionIntent(int blockX, int blockZ, Climate.Sampler sampler) {
        boolean cacheHitBeforeQuery = surfacePatchGrid().peekRegionIntent(blockX, blockZ) != null;
        SurfacePatchGrid.BiomeAndFamily resolved = surfacePatchGrid().resolveWithFamily(blockX, blockZ, sampler);
        if (resolved == null) {
            return null;
        }
        return new RegionIntentDebugInfo(resolved.biome(), resolved.regionFamily(), cacheHitBeforeQuery);
    }

    public record RegionIntentDebugInfo(ResourceKey<Biome> biome, RegionFamily regionFamily, boolean cacheHitBeforeQuery) {
    }

    private SurfacePatchGrid surfacePatchGrid() {
        SurfacePatchGrid grid = surfacePatchGrid;
        if (grid == null) {
            synchronized (this) {
                grid = surfacePatchGrid;
                if (grid == null) {
                    grid = new SurfacePatchGrid(WorldSeedHolder.overworldSeed(), BiomeConfigRegistry.allConfigs());
                    surfacePatchGrid = grid;
                    // Phase 2 (Stage 3): a codec-constructed DensityFunction has no other
                    // way to reach this instance -- see SurfacePatchGridHolder's own
                    // comment.
                    SurfacePatchGridHolder.set(grid);
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
