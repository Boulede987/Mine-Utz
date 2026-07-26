package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.BiomeConfig;

import java.util.List;

/**
 * Picks a biome for a TRUE GAP column (no patch's growth ever reached it) using a
 * SMOOTHLY-VARYING selector instead of an independent per-column random roll.
 *
 * Real WorldgenDebugDump data (452k then 431k logged real columns across two fresh
 * worlds) confirmed gap columns are common -- 52% of the surface, then 33% after
 * retuning PatchGrowthEngine's germination spacing -- and resolved via an uncorrelated
 * per-column dice roll (this class's predecessor logic, formerly inline in
 * CustomOverworldBiomeSource.pickTerrainAppropriateFallback), produced visible chaotic
 * salt-and-pepper patches even after fixing the underlying growth/spacing algorithm
 * issues: spatial bucket analysis showed 57-71% of gap-dominated 128-block areas had 3+
 * distinct biomes crammed together. This does NOT reduce how much of the map resolves
 * via the gap-fallback path -- only how coherent that fallback area looks.
 *
 * Mechanism: instead of PatchSeedHasher.unitIntervalFromSeed's per-exact-block-hashed
 * random roll, use DomainWarp's proven smooth multi-octave-style lattice noise (same
 * bilinearSmoothstep machinery Stage 3's RegionIntentDensityFunction already reuses,
 * package-visible for exactly this kind of cross-consumer reuse) as the weighted-pick
 * "roll" fed into PatchGrowthEngine.pickWeightedBiomeByRoll. Since the noise field
 * varies continuously and each biome's cumulative-weight range is fixed, nearby gap
 * columns land on similar roll values and tend to pick the SAME biome, only flipping to
 * a neighbor when the smooth field crosses a cumulative-weight boundary -- organic,
 * patch-like clustering instead of independent noise. Entirely deterministic (a pure
 * function of world seed + position, no caching or resolution-order dependency),
 * matching this codebase's own order-independence guarantee (see PatchGrowthEngine's
 * class comment and its concurrent-call determinism self-test).
 */
public final class CoherentFallbackPicker {

    // Lattice spacing for the smooth selector field -- deliberately much wider than
    // SurfacePatchGrid.CELL_SIZE_BLOCKS (16) so one fallback "region" spans roughly a
    // real patch's own scale, not a handful of cells. Not tied to any specific biome's
    // own size config on purpose: this path exists precisely because no patch's own
    // size-driven growth ever reached here, so there's no single biome's scale to
    // anchor to -- a fixed, generously wide value instead. Not a tuned value; revisit if
    // fallback regions still look too small/large once seen in a real world.
    private static final int SELECTOR_LATTICE_SPACING_BLOCKS = 256;

    // Distinct from every other hash-namespace use in this codebase (PatchGrowthEngine's
    // 0/1/3, UndergroundPatchGrid's 2, DomainWarp's 10/20+) -- keeps this noise field
    // decorrelated from every other seeded value derived from the same world seed.
    private static final int SELECTOR_HASH_NAMESPACE = 50;

    private CoherentFallbackPicker() {
    }

    /** candidates must be non-empty -- callers (CustomOverworldBiomeSource) already
     * guarantee this, same precondition pickWeightedBiome's other callers rely on. */
    public static BiomeConfig pick(long worldSeed, int blockX, int blockZ, List<BiomeConfig> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0); // nothing to select between, skip the noise sample entirely
        }
        double smoothUnitRoll = (smoothSelectorValue(worldSeed, blockX, blockZ) + 1.0) / 2.0;
        return PatchGrowthEngine.pickWeightedBiomeByRoll(candidates, smoothUnitRoll);
    }

    /** Returns a value in [-1, 1], smoothly interpolated -- same convention as
     * DomainWarp's own noise (see its class comment), which is what makes this
     * combinable with that machinery in the first place. */
    private static double smoothSelectorValue(long worldSeed, int blockX, int blockZ) {
        return DomainWarp.bilinearSmoothstep(blockX, blockZ, SELECTOR_LATTICE_SPACING_BLOCKS,
            (latticeX, latticeZ) -> latticePointValue(worldSeed, latticeX, latticeZ));
    }

    private static double latticePointValue(long worldSeed, int latticeX, int latticeZ) {
        long seed = PatchSeedHasher.cellSeed(worldSeed, latticeX, SELECTOR_HASH_NAMESPACE, latticeZ);
        return PatchSeedHasher.unitIntervalFromSeed(seed) * 2.0 - 1.0;
    }
}
