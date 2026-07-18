package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.BiomeConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Underground tier, requirement 3's "exact 1:1 linkage": a cave biome's eligibility is
 * gated on which specific surface biome patch occupies the column above it, via
 * SurfacePatchGrid's own memoized cache -- this dependency is strictly one-directional
 * (underground reads from the surface tier, never the reverse). If that call direction
 * were ever inverted, a SurfacePatchGrid resolution calling back into an
 * UndergroundPatchGrid that itself calls back into the SAME SurfacePatchGrid would be a
 * re-entrant computeIfAbsent on the same map, which throws on real JDKs -- keep it
 * one-directional.
 *
 * Deliberately simpler than the surface tier: no growth, no size/adjacency (requirement
 * 3 only asked for linkage, not underground patch size/shape control). Each 32-block
 * coarse cell independently rolls a weighted pick among whichever cave biomes are
 * linked to the surface biome directly above it -- memoized per cell in a
 * ConcurrentHashMap the same way SurfacePatchGrid is, for the same reason (real
 * concurrency, no redundant recompute). A column with no cave biome linked to its
 * surface biome resolves to null -- the caller falls back (see
 * CustomOverworldBiomeSource.getNoiseBiome), it must never invent an arbitrary biome for
 * the gap, same rule as the surface tier.
 */
public final class UndergroundPatchGrid {

    private static final int CELL_SIZE_BLOCKS = 32;

    // Separate hash namespace from the surface tier's own uses (0 = germination
    // candidate selection, 1 = patch identity) so this doesn't collide with either.
    private static final int UNDERGROUND_HASH_NAMESPACE = 2;

    private final long worldSeed;
    private final List<BiomeConfig> undergroundBiomes;
    private final SurfacePatchGrid surfacePatchGrid;
    private final ConcurrentHashMap<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>> resolvedCells = new ConcurrentHashMap<>();

    /** biomes should be the full configured biome list -- this filters to only the ones
     * with a non-empty undergroundLinkedSurfaceBiomes itself, so the caller doesn't have
     * to pre-filter. */
    public UndergroundPatchGrid(long worldSeed, List<BiomeConfig> biomes, SurfacePatchGrid surfacePatchGrid) {
        this.worldSeed = worldSeed;
        this.undergroundBiomes = biomes.stream().filter(biome -> !biome.undergroundLinkedSurfaceBiomes().isEmpty()).toList();
        this.surfacePatchGrid = surfacePatchGrid;
    }

    public ResourceKey<Biome> resolve(int blockX, int blockZ, Climate.Sampler sampler) {
        if (undergroundBiomes.isEmpty()) {
            return null;
        }
        PatchGrowthEngine.CoarseCell cell = new PatchGrowthEngine.CoarseCell(
            Math.floorDiv(blockX, CELL_SIZE_BLOCKS), Math.floorDiv(blockZ, CELL_SIZE_BLOCKS)
        );
        return resolvedCells.computeIfAbsent(cell, key -> resolveCell(key, sampler));
    }

    /** Runs inside ConcurrentHashMap.computeIfAbsent -- must never call back into
     * resolvedCells (re-entrant computeIfAbsent on the same map throws). It calls into
     * SurfacePatchGrid instead, a different map entirely, which is fine. */
    private ResourceKey<Biome> resolveCell(PatchGrowthEngine.CoarseCell cell, Climate.Sampler sampler) {
        int representativeBlockX = cell.cellX() * CELL_SIZE_BLOCKS + CELL_SIZE_BLOCKS / 2;
        int representativeBlockZ = cell.cellZ() * CELL_SIZE_BLOCKS + CELL_SIZE_BLOCKS / 2;
        ResourceKey<Biome> surfaceBiome = surfacePatchGrid.resolve(representativeBlockX, representativeBlockZ, sampler);
        if (surfaceBiome == null) {
            return null;
        }

        List<BiomeConfig> eligible = undergroundBiomes.stream()
            .filter(biome -> biome.undergroundLinkedSurfaceBiomes().contains(surfaceBiome))
            .toList();
        if (eligible.isEmpty()) {
            return null;
        }

        long seed = PatchSeedHasher.cellSeed(worldSeed, cell.cellX(), UNDERGROUND_HASH_NAMESPACE, cell.cellZ());
        return PatchGrowthEngine.pickWeightedBiome(eligible, seed).id();
    }
}
