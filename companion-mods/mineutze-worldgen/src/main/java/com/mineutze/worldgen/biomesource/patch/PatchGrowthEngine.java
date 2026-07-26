package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.BiomeConfig;
import com.mineutze.worldgen.biomesource.RegionFamily;
import com.mineutze.worldgen.biomesource.RegionFamilyWeight;
import com.mineutze.worldgen.biomesource.TerrainClass;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;

/**
 * Seeded best-first patch-growth biome placement, resolved over a bounded rectangular
 * window of coarse cells. Germination uses a local-maximum rule (order-independent),
 * growth uses a global best-first priority queue with a deterministic per-(cell, patch)
 * tie-break, so the result never depends on which order cells are visited in --
 * required because real chunk generation visits cells in whatever order a player
 * explores, not a fixed scan. Determinism verified by {@link PatchGrowthEngineSelfTest}
 * (`./gradlew selfTest`).
 *
 * A patch's identity is its germination cell's own coordinates (the CoarseCell key in
 * patchesByCell), not an incrementing counter -- an earlier version (this class's
 * now-dropped Python prototype) used enumeration order for patch identity and it leaked
 * into the tie-break hash, breaking determinism across scan orders. Coordinates are
 * already order-independent, so this class doesn't need to guard against that mistake
 * at all.
 *
 * Bounded window: SurfacePatchGrid (the caller) picks a window around a queried cell
 * and this class resolves every cell inside it in one batch, memoized by the caller
 * afterwards. Cells near the window edge can, in principle, differ from what an
 * unbounded resolution would produce (their patch might have kept growing past the
 * window edge) -- an accepted tradeoff, not a bug; see DESIGN.md Sec 6's "bounded
 * lookahead" requirement. Window size needs to stay comfortably larger than the
 * largest configured biome's size_max to keep this a rare edge case in practice.
 */
public final class PatchGrowthEngine {

    private static final int[][] CARDINAL_NEIGHBOR_OFFSETS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final double PREFERRED_NEIGHBOR_SCORE_BONUS   = 0.5;
    private static final double FORBIDDEN_NEIGHBOR_SCORE_PENALTY = 0.5;
    private static final double TIE_BREAK_SCORE_SCALE            = 1e-6;
    private static final int MINIMUM_SPACING_CELLS               = 1;

    // Real reported symptom: at every real FLAT_LAND/HILLY boundary, biomes that don't
    // even declare one side of it (windswept_hills invading real FLAT_LAND; plains/forest
    // invading real HILLY) were contesting cells on completely equal footing with biomes
    // that DO declare that exact class -- turning every such boundary into a chaotic,
    // many-small-patches battlefield, not just windswept_hills/forest/plains but any pair
    // sharing the blend. Considered and rejected: disabling GROWTH_BLENDABLE_LAND_TERRAIN_
    // CLASSES eligibility outright (requiring a biome to declare BOTH FLAT_LAND and HILLY
    // to blend at all) -- turns out mathematically equivalent to disabling the blend
    // entirely, since any biome declaring both already passes exact-match on either class
    // with no blend needed; the blend only ever mattered for biomes declaring exactly ONE
    // of the two, which is precisely the population that fix would strip it from. That
    // would also undo growth-blend's actual documented purpose (not noise-smoothing --
    // giving a patch real territory beyond wherever real Tectonic terrain already happens
    // to match, so Stage 3/4's terrain-forcing has something to act on).
    //
    // This penalty keeps blend-reliant growth POSSIBLE (so that purpose still holds when
    // nothing else is contesting the cell) but SCORES it below a same-class competitor,
    // so at a real boundary a biome that actually declares the cell's real class usually
    // wins the contest first -- same order of magnitude as FORBIDDEN_NEIGHBOR_SCORE_
    // PENALTY, enough to matter against typical BiomeConfig weights (0.6-1.0) without
    // being an outright block.
    private static final double GROWTH_BLEND_RELIANCE_SCORE_PENALTY = 0.5;

    // Real reported symptom: two biomes sharing a terrain class and similar weight (e.g.
    // plains/forest, both FLAT_LAND) produced a visibly noisy, scattered border -- small
    // alternating freckles of each biome before eventually settling into solid territory
    // further in. Root cause: without this bonus, every contested cell along a shared
    // frontier was decided purely by weight + the ~1e-6 tie-break noise, with nothing
    // rewarding a cell for being surrounded by more of its own already-resolved
    // territory. This is deliberately a same-BIOME check, not same-PATCH -- two separate
    // patches of the same biome sitting near each other should still cohere, and
    // visually "which patch instance" is invisible to a player anyway. Orthogonal to
    // PREFERRED_NEIGHBOR_SCORE_BONUS: that rewards bordering a *different*,
    // macro-preferred biome (encourages patches to cluster near each other in the first
    // place); this rewards bordering *more of the same* biome (keeps any single patch's
    // own edge compact once it's growing) -- both can and do apply to the same cell.
    private static final double SAME_BIOME_COHESION_BONUS_PER_NEIGHBOR = 0.2;

    // A germination point deep inside what will become a much larger neighboring
    // patch's territory can survive its own local-maximum check (nothing else was
    // within ITS spacing radius at germination time) and then get boxed in almost
    // immediately once the surrounding patch grows around it -- a small, isolated
    // "island" of one biome inside another. Real, reported symptom, not a hypothetical:
    // a component smaller than this fraction of its own biome's expected minimum size
    // is noise, not an intentionally small patch, and gets dissolved into whichever
    // neighboring biome borders it most (see dissolveSmallIslands). Only ever reassigns
    // into a biome that's terrain-class-eligible for every cell involved -- dissolving
    // into an ineligible biome would silently undo Milestone 3's hard terrain-class gate.
    private static final double SMALL_ISLAND_DISSOLVE_FRACTION = 0.3;

    // cellSeed's cellY parameter doubles as a hash "namespace" selector: 0 for
    // germination-candidate selection, 1 for patch-identity seeding, 3 for region-family
    // rolling (2 is UndergroundPatchGrid's, 10/20+ are DomainWarp's) -- keeps every hash
    // use from colliding.
    private static final int GERMINATION_CANDIDATE_HASH_NAMESPACE = 0;
    private static final int PATCH_IDENTITY_HASH_NAMESPACE        = 1;
    private static final int REGION_FAMILY_HASH_NAMESPACE         = 3;

    // Phase 2 (TERRAIN_COHERENCE_DESIGN.md Sec 4a/4b): OCEAN/COAST/RIVER stay gated by
    // the REAL terrain class here -- Tectonic's base elevation axis is physically
    // separate from the region-family shape axis (Sec 4b's confirmed finding), so an
    // intent-driven desert placed on real deep ocean would produce underwater dunes, not
    // land.
    //
    // FLAT_LAND/HILLY/MOUNTAINOUS split GERMINATION from GROWTH -- a real, in-game
    // correction to the first attempt at this, which made BOTH terrain-blind (any biome
    // declaring any land class eligible on any land cell, everywhere, for everything).
    // That collapsed 3 real, naturally-clustered terrain classes into one undifferentiated
    // bucket: a biome like windswept_hills (previously confined to real hills/mountains --
    // rare, clustered terrain) could suddenly GERMINATE anywhere on any land cell in the
    // whole world, since every land cell now offered it as an equally-eligible candidate.
    // Confirmed real symptom in-game: small windswept_hills/desert/etc patches peppered
    // through completely unrelated terrain (forest, plains) at high density -- not a
    // mismatch between biome and terrain SHAPE (expected and fine at this stage, see
    // Stage 1's own doc note), but the biome MAP ITSELF losing the macro-scale coherence
    // real terrain-class boundaries used to provide for free.
    //
    // Fix: germination (isEligibleForGermination) stays exactly as strict as Phase 1
    // always was -- a patch can only ORIGINATE where real terrain already matches one of
    // its declared classes, for every TerrainClass, water or land alike. Growth
    // (isEligibleForGrowth) is where terrain-blindness actually lives -- an
    // already-germinated patch can SPREAD onto adjacent land cells regardless of their
    // real class. This still achieves the real goal (an existing patch's territory isn't
    // artificially bounded by real terrain shape, so Stage 3/4's eventual terrain-forcing
    // has real territory to act on) without exploding germination density across
    // unrelated terrain everywhere.
    private static final Set<TerrainClass> WATER_TERRAIN_CLASSES = Set.of(
        TerrainClass.OCEAN, TerrainClass.COAST, TerrainClass.RIVER
    );
    private static final Set<TerrainClass> LAND_TERRAIN_CLASSES = Set.of(
        TerrainClass.FLAT_LAND, TerrainClass.HILLY, TerrainClass.MOUNTAINOUS
    );
    // Growth-blend-only subset of LAND_TERRAIN_CLASSES -- MOUNTAINOUS deliberately
    // excluded. Real in-game evidence (a desert patch, declaring only FLAT_LAND/COAST,
    // grown directly onto a real MOUNTAINOUS cell across a genuine coast-to-mountain
    // transition, confirmed via /dump_terrain_class showing smooth, non-noisy real
    // classification either side of the border -- so this was never a classifier-noise
    // problem) showed FLAT_LAND/HILLY/MOUNTAINOUS treated as one fully-interchangeable
    // bucket for growth was too permissive: real mountainous terrain is visually far
    // more distinct (elevation, jaggedness) from flat/hilly than flat and hilly are from
    // each other, and until Stage 3/4 actually forces terrain shape to comply with
    // biome intent, letting non-mountain biomes grow across real mountains produces a
    // large, jarring mismatch, not a gentle blur. FLAT_LAND/HILLY still blend freely
    // (much closer in character); MOUNTAINOUS goes back to requiring an exact real-class
    // match during growth too, same strictness as water.
    private static final Set<TerrainClass> GROWTH_BLENDABLE_LAND_TERRAIN_CLASSES = Set.of(
        TerrainClass.FLAT_LAND, TerrainClass.HILLY
    );

    @FunctionalInterface
    public interface TerrainSampler {
        TerrainClass classify(int cellX, int cellZ);
    }

    public record CoarseCell(int cellX, int cellZ) {
    }

    private record GerminationCandidate(BiomeConfig biome, int spacingCells, double priority) {
    }

    private static final class GrowingPatch {
        final BiomeConfig biome;
        final double targetRadiusBlocks;
        final long identitySeed;
        // Rolled once per patch at germination (not per cell) -- every cell this patch
        // claims shares one shape intent, see resolveWindow. Null for any biome with an
        // empty regionFamilyWeights (OCEAN/RIVER/underground) -- those never populate
        // regionFamilyByCell at all, which is exactly what later stages' cache-peek reads
        // as "stay reactive to real Tectonic terrain here".
        final RegionFamily regionFamily;
        int cellCount = 1;

        GrowingPatch(BiomeConfig biome, double targetRadiusBlocks, long identitySeed, RegionFamily regionFamily) {
            this.biome = biome;
            this.targetRadiusBlocks = targetRadiusBlocks;
            this.identitySeed = identitySeed;
            this.regionFamily = regionFamily;
        }
    }

    private record FrontierEntry(
        double negativeScore, long pushOrder, CoarseCell cell, CoarseCell patchOrigin
    ) implements Comparable<FrontierEntry> {
        @Override
        public int compareTo(FrontierEntry other) {
            int scoreComparison = Double.compare(negativeScore, other.negativeScore);
            return scoreComparison != 0 ? scoreComparison : Long.compare(pushOrder, other.pushOrder);
        }
    }

    private PatchGrowthEngine() {
    }

    /** Both maps share the same key set for every cell a patch actually claimed;
     * regionFamilyByCell simply has no entry for cells whose patch's biome declared no
     * regionFamilyWeights (see GrowingPatch's own comment) -- absence, not null, is the
     * "stay reactive to real Tectonic terrain" signal later stages read. */
    public record WindowResolution(
        Map<CoarseCell, ResourceKey<Biome>> biomeByCell,
        Map<CoarseCell, RegionFamily> regionFamilyByCell
    ) {
    }

    public static WindowResolution resolveWindow(
        long worldSeed,
        List<BiomeConfig> biomes,
        double cellSizeBlocks,
        TerrainSampler terrainSampler,
        int minCellX, int minCellZ, int maxCellXExclusive, int maxCellZExclusive
    ) {
        WindowBounds bounds = new WindowBounds(minCellX, minCellZ, maxCellXExclusive, maxCellZExclusive);
        // One real Climate.Sampler call per window cell, cached here, so germination
        // eligibility and growth-time eligibility never re-sample the same cell twice --
        // real noise evaluation is not free, and every cell in this window is visited by
        // precompute regardless, so caching costs nothing extra.
        Map<CoarseCell, TerrainClass> terrainClassByCell = classifyWindow(terrainSampler, bounds);

        Map<CoarseCell, GerminationCandidate> candidatesByCell = precomputeGerminationCandidates(
            worldSeed, biomes, cellSizeBlocks, terrainClassByCell
        );
        List<CoarseCell> germinationPoints = selectGerminationPoints(candidatesByCell);

        Map<CoarseCell, GrowingPatch> patchesByCell = new HashMap<>();
        Map<CoarseCell, ResourceKey<Biome>> resolvedCells = new HashMap<>();
        Map<CoarseCell, RegionFamily> regionFamilyByCell = new HashMap<>();
        for (CoarseCell origin : germinationPoints) {
            GerminationCandidate candidate = candidatesByCell.get(origin);
            long identitySeed = PatchSeedHasher.cellSeed(worldSeed, origin.cellX(), PATCH_IDENTITY_HASH_NAMESPACE, origin.cellZ());
            RegionFamily regionFamily = rollRegionFamily(worldSeed, origin, candidate.biome());
            GrowingPatch patch = new GrowingPatch(candidate.biome(), targetRadiusFromCandidate(candidate), identitySeed, regionFamily);
            patchesByCell.put(origin, patch);
            resolvedCells.put(origin, candidate.biome().id());
            if (regionFamily != null) {
                regionFamilyByCell.put(origin, regionFamily);
            }
        }

        PriorityQueue<FrontierEntry> frontier = new PriorityQueue<>();
        AtomicLong pushCounter = new AtomicLong();
        for (CoarseCell origin : germinationPoints) {
            pushEligibleNeighbors(frontier, pushCounter, origin, origin, patchesByCell.get(origin), resolvedCells, terrainClassByCell, bounds);
        }
        growUntilFrontierEmpty(frontier, pushCounter, patchesByCell, resolvedCells, regionFamilyByCell, terrainClassByCell, bounds, cellSizeBlocks);
        dissolveSmallIslands(resolvedCells, regionFamilyByCell, terrainClassByCell, biomes, cellSizeBlocks);
        return new WindowResolution(resolvedCells, regionFamilyByCell);
    }

    /** Null (no roll at all) for any biome that declares no regionFamilyWeights -- those
     * biomes stay reactive to real Tectonic terrain (see GrowingPatch's own comment). */
    private static RegionFamily rollRegionFamily(long worldSeed, CoarseCell origin, BiomeConfig biome) {
        if (biome.regionFamilyWeights().isEmpty()) {
            return null;
        }
        long seed = PatchSeedHasher.cellSeed(worldSeed, origin.cellX(), REGION_FAMILY_HASH_NAMESPACE, origin.cellZ());
        return pickWeightedRegionFamily(biome.regionFamilyWeights(), seed);
    }

    /** Inclusive/exclusive coarse-cell rectangle a single resolveWindow call is allowed
     * to touch. Growth MUST stay inside this rectangle regardless of target radius --
     * without this check, a biome with a size_max larger than the window (or, worse, an
     * effectively-unbounded placeholder config) grows forever outward, sampling
     * Climate.Sampler an unbounded number of times. That bug is what hung world creation
     * on "Scanning for structures": the eager stronghold-placement ring search triggered
     * a cache miss whose growth phase never terminated within a sane time budget. */
    private record WindowBounds(int minCellX, int minCellZ, int maxCellXExclusive, int maxCellZExclusive) {
        boolean contains(CoarseCell cell) {
            return cell.cellX() >= minCellX && cell.cellX() < maxCellXExclusive
                && cell.cellZ() >= minCellZ && cell.cellZ() < maxCellZExclusive;
        }
    }

    private static Map<CoarseCell, TerrainClass> classifyWindow(TerrainSampler terrainSampler, WindowBounds bounds) {
        Map<CoarseCell, TerrainClass> terrainClassByCell = new HashMap<>();
        for (int cellX = bounds.minCellX(); cellX < bounds.maxCellXExclusive(); cellX++) {
            for (int cellZ = bounds.minCellZ(); cellZ < bounds.maxCellZExclusive(); cellZ++) {
                terrainClassByCell.put(new CoarseCell(cellX, cellZ), terrainSampler.classify(cellX, cellZ));
            }
        }
        return terrainClassByCell;
    }

    /** Pure function of (worldSeed, cellX, cellZ) for every cell -- iteration order over
     * the window never affects the result, only which cells are considered at all. */
    private static Map<CoarseCell, GerminationCandidate> precomputeGerminationCandidates(
        long worldSeed, List<BiomeConfig> biomes, double cellSizeBlocks, Map<CoarseCell, TerrainClass> terrainClassByCell
    ) {
        Map<CoarseCell, GerminationCandidate> candidatesByCell = new HashMap<>();
        for (Map.Entry<CoarseCell, TerrainClass> entry : terrainClassByCell.entrySet()) {
            CoarseCell cell = entry.getKey();
            List<BiomeConfig> eligible = eligibleBiomesForTerrainClass(biomes, entry.getValue());
            if (eligible.isEmpty()) {
                continue;
            }
            long seed = PatchSeedHasher.cellSeed(worldSeed, cell.cellX(), GERMINATION_CANDIDATE_HASH_NAMESPACE, cell.cellZ());
            BiomeConfig chosenBiome = pickWeightedBiome(eligible, seed);
            int spacingCells = spacingRadiusInCells(chosenBiome, cellSizeBlocks);
            double priority = PatchSeedHasher.unitIntervalFromSeed(PatchSeedHasher.mix64Bits(seed));
            candidatesByCell.put(cell, new GerminationCandidate(chosenBiome, spacingCells, priority));
        }
        return candidatesByCell;
    }

    private static List<BiomeConfig> eligibleBiomesForTerrainClass(List<BiomeConfig> biomes, TerrainClass terrainClass) {
        List<BiomeConfig> eligible = new ArrayList<>();
        for (BiomeConfig biome : biomes) {
            if (isEligibleForGermination(biome, terrainClass)) {
                eligible.add(biome);
            }
        }
        return eligible;
    }

    /** Where a patch is allowed to ORIGINATE -- exact real-terrain-class match required
     * for every class, water or land, same as Phase 1 always required. See the class-level
     * comment above WATER_TERRAIN_CLASSES/LAND_TERRAIN_CLASSES for why this is
     * deliberately stricter than isEligibleForGrowth below. */
    private static boolean isEligibleForGermination(BiomeConfig biome, TerrainClass realTerrainClass) {
        return biome.terrainClasses().contains(realTerrainClass);
    }

    /** Where an ALREADY-GERMINATED patch is allowed to SPREAD. OCEAN/COAST/RIVER and
     * MOUNTAINOUS all require an exact match -- water stays reactive to real elevation
     * everywhere, and MOUNTAINOUS is excluded from the blend for the reason in
     * GROWTH_BLENDABLE_LAND_TERRAIN_CLASSES' own comment. FLAT_LAND/HILLY are blind to
     * which of the two a neighbor cell is, as long as the spreading biome declares
     * either. See the class-level comment for why this split from germination exists,
     * and isEligibleForGermination for the stricter sibling.
     *
     * Package-visible (not private): SurfacePatchGrid (same package) reuses this exact
     * rule for its own per-exact-block safety net -- a narrow real feature (RIVER
     * especially, often narrower than one CELL_SIZE_BLOCKS cell) can sit at a block whose
     * CELL was classified something else by classifyCell's single-midpoint sample. That
     * safety net must reject only what growth itself would never have allowed (a real
     * water-class or MOUNTAINOUS mismatch), not legitimate FLAT_LAND/HILLY blending --
     * this is exactly that boundary, already correct, don't duplicate it. */
    static boolean isEligibleForGrowth(BiomeConfig biome, TerrainClass realTerrainClass) {
        if (GROWTH_BLENDABLE_LAND_TERRAIN_CLASSES.contains(realTerrainClass)) {
            return biome.terrainClasses().stream().anyMatch(GROWTH_BLENDABLE_LAND_TERRAIN_CLASSES::contains);
        }
        return biome.terrainClasses().contains(realTerrainClass);
    }

    /** Public: reused by UndergroundPatchGrid (same package, choosing among multiple cave
     * biomes linked to the same surface biome) and CustomOverworldBiomeSource's gap
     * fallback (different package, picking among biomes eligible for a gap cell's real
     * terrain class). */
    public static BiomeConfig pickWeightedBiome(List<BiomeConfig> candidates, long seed) {
        return pickWeightedByRoll(candidates, BiomeConfig::weight, PatchSeedHasher.unitIntervalFromSeed(seed));
    }

    /** Same weighted-pick pattern as pickWeightedBiome, one level down -- a biome's
     * regionFamilyWeights (TERRAIN_COHERENCE_DESIGN.md Sec 4c: a weighted SET per biome,
     * not a fixed 1:1 pick, to avoid every patch of a biome rendering the same Tectonic
     * shape template). Callers must not pass an empty list -- see rollRegionFamily's own
     * empty check, which is what keeps this from ever being called with one. */
    public static RegionFamily pickWeightedRegionFamily(List<RegionFamilyWeight> candidates, long seed) {
        return pickWeightedByRoll(candidates, RegionFamilyWeight::weight, PatchSeedHasher.unitIntervalFromSeed(seed)).family();
    }

    /** Public: CoherentFallbackPicker (same package) reuses this with a SMOOTHLY-VARYING
     * roll (not a per-cell-independent seed hash) so gap-fallback biome selection
     * clusters spatially instead of flipping independently cell to cell -- see that
     * class's own comment for why. unitRoll should be in [0, 1); pickWeightedBiome's own
     * seed-to-roll conversion is exactly PatchSeedHasher.unitIntervalFromSeed, now
     * factored out here as the shared cumulative-weight walk both entry points share. */
    public static BiomeConfig pickWeightedBiomeByRoll(List<BiomeConfig> candidates, double unitRoll) {
        return pickWeightedByRoll(candidates, BiomeConfig::weight, unitRoll);
    }

    private static <T> T pickWeightedByRoll(List<T> candidates, ToDoubleFunction<T> weightOf, double unitRoll) {
        double totalWeight = 0;
        for (T candidate : candidates) {
            totalWeight += weightOf.applyAsDouble(candidate);
        }
        if (totalWeight <= 0) {
            totalWeight = 1.0;
        }
        double roll = unitRoll * totalWeight;
        double runningTotal = 0;
        for (T candidate : candidates) {
            runningTotal += weightOf.applyAsDouble(candidate);
            if (roll <= runningTotal) {
                return candidate;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    // Cause B (real in-game symptom: desert/savanna/plains and other same-terrain-class
    // biomes fragmenting each other into many small patches). Root cause: this used to
    // read biome.sizeMinBlocks() -- the germination-time local-maximum spacing check
    // (isLocalMaximum below) only ever guaranteed enough separation for a patch's SMALLEST
    // possible size, while targetRadiusFromCandidate actually samples the real grown
    // radius uniformly across the full [sizeMin/2, sizeMax/2] range. A biome with a wide
    // min-max range (desert: 900-1900, roughly 2x) could legitimately roll toward its
    // size_max during growth and reach well past where a neighboring biome's germination
    // point was only guaranteed room for -- their frontiers then collide mid-growth. Where
    // several biomes share one terrain class (FLAT_LAND: desert/savanna/plains/
    // snowy_plains) and multiple such near-collisions land close together, this produced
    // an interleaved battlefield of small patches, not a calibration/noise problem.
    //
    // REVISED again after real WorldgenDebugDump data (see git history): the sizeMax-based
    // fix above was itself wrong, confirmed empirically, not just theoretically. Real
    // world data (452k logged columns) showed 52% of all surface resolutions were true
    // gaps -- no patch's growth ever reached them, resolved instead by
    // CustomOverworldBiomeSource.pickTerrainAppropriateFallback, an INDEPENDENT per-column
    // weighted roll with zero spatial correlation between neighboring columns. Spatially:
    // 71% of surface-tier 128-block buckets were >70% gap rows, and 65% of THOSE had 3+
    // distinct biomes crammed into one bucket -- the exact chaotic salt-and-pepper pattern
    // being debugged, at map scale, not just at biome seams.
    //
    // Root cause of the regression: sizeMaxBlocks-based spacing guards against the WORST
    // CASE (both neighboring patches simultaneously roll their maximum size), but
    // targetRadiusFromCandidate samples the real grown radius UNIFORMLY across
    // [sizeMin/2, sizeMax/2] -- so most patches roll somewhere in the middle, not the max.
    // Spacing sized for the rare worst case left enormous unclaimed area around the
    // TYPICAL patch, collapsing real coverage rather than just reducing collisions.
    //
    // Fixed by spacing on the MIDPOINT (sizeMin + sizeMax) / 2 instead -- the actual
    // expected value of that uniform distribution, not a worst-case bound. Statistically
    // principled middle ground: still meaningfully wider than the original sizeMin-only
    // spacing (still reduces the real collision problem Cause B was chasing), without
    // sizing for a scenario that rarely happens at the cost of gutting normal coverage.
    // Expect to re-measure the real gap rate with WorldgenDebugDump after this change,
    // not assume it's fully solved from reasoning alone -- this file has now been wrong
    // twice in a row trusting theory over real data.
    private static int spacingRadiusInCells(BiomeConfig biome, double cellSizeBlocks) {
        double midpointSizeBlocks = (biome.sizeMinBlocks() + biome.sizeMaxBlocks()) / 2.0;
        return Math.max(MINIMUM_SPACING_CELLS, (int) Math.round(midpointSizeBlocks / (2 * cellSizeBlocks)));
    }

    /** Test-support entry point (package-visible, not private): lets
     * PatchGrowthEngineSelfTest assert spacing scales with sizeMaxBlocks (the Cause B
     * fix) rather than sizeMinBlocks (the old, buggy behavior) directly, without needing
     * to reverse-engineer it from germination-origin positions in a full resolveWindow
     * run -- WindowResolution doesn't expose which cells were origins vs. grown-into. */
    static int spacingRadiusInCellsForTest(BiomeConfig biome, double cellSizeBlocks) {
        return spacingRadiusInCells(biome, cellSizeBlocks);
    }

    /** Order-independent: see isLocalMaximum's docstring below. */
    private static List<CoarseCell> selectGerminationPoints(Map<CoarseCell, GerminationCandidate> candidatesByCell) {
        List<CoarseCell> germinationPoints = new ArrayList<>();
        for (Map.Entry<CoarseCell, GerminationCandidate> entry : candidatesByCell.entrySet()) {
            if (isLocalMaximum(entry.getKey(), entry.getValue(), candidatesByCell)) {
                germinationPoints.add(entry.getKey());
            }
        }
        return germinationPoints;
    }

    // REVISED after real WorldgenDebugDump data (see git history): the suppression
    // radius used to be Math.max(candidate.spacingCells(), neighbor.spacingCells()) --
    // always the LARGER of the two competing biomes' own configured spacing. Since
    // desert has by far the widest spacing radius among configured biomes (sizeMax=1900,
    // ~44 cells under the current midpoint-based formula), that max() meant desert (and
    // to a lesser extent ocean) could suppress germination for ANY other biome within
    // its own huge radius, REGARDLESS of whether they even share a terrain class --
    // isLocalMaximum only compares priority values, it never checks terrain-class
    // relevance between the two candidates being compared. Confirmed empirically, not
    // just theoretically: real dumped data showed forest/savanna/snowy_plains/river at
    // 0% real ("surface" source) resolution, windswept_hills/desert at 0.4-0.7% -- nearly
    // all resolved via the gap-fallback path instead, meaning germination for these
    // biomes was barely happening at all. Widening every biome's spacing radius (this
    // file's own Cause B history) made this pre-existing asymmetry worse, not new.
    //
    // Fixed by using ONLY the evaluated candidate's own spacingCells -- each biome's own
    // configured scale now governs its own suppression sensitivity, never inflated by an
    // unrelated neighbor's larger scale. A large-range biome (desert) still gets
    // correctly suppressed by any higher-priority neighbor within ITS OWN large reach
    // (its own check still uses its own large radius); a small-range biome (forest) only
    // ever worries about its own, much smaller neighborhood. This also means the loop
    // bound no longer needs a separately-computed global maximum (selectGerminationPoints
    // no longer needs lookaheadRadiusCells at all) and the old withinRadius check is
    // redundant (the loop bounds already only ever reach candidate's own radius).
    private static boolean isLocalMaximum(
        CoarseCell cell, GerminationCandidate candidate, Map<CoarseCell, GerminationCandidate> candidatesByCell
    ) {
        int radius = candidate.spacingCells();
        for (int deltaX = -radius; deltaX <= radius; deltaX++) {
            for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
                if (deltaX == 0 && deltaZ == 0) {
                    continue;
                }
                GerminationCandidate neighbor = candidatesByCell.get(new CoarseCell(cell.cellX() + deltaX, cell.cellZ() + deltaZ));
                if (neighbor != null && neighbor.priority() > candidate.priority()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Test-support entry point (package-visible, not private): lets
     * PatchGrowthEngineSelfTest verify isLocalMaximum's asymmetric-radius behavior
     * directly, with explicit test-controlled priority/spacing values -- the real
     * hash-derived candidate generation (precomputeGerminationCandidates) can't easily
     * be forced into a specific priority collision, which is exactly what this fix is
     * about. GerminationCandidate.biome is never read by isLocalMaximum, so null is
     * safe here. */
    static boolean isLocalMaximumForTest(
        int candidateSpacingCells, double candidatePriority,
        int neighborOffsetXCells, int neighborOffsetZCells, double neighborPriority
    ) {
        CoarseCell origin = new CoarseCell(0, 0);
        CoarseCell neighborCell = new CoarseCell(neighborOffsetXCells, neighborOffsetZCells);
        GerminationCandidate candidate = new GerminationCandidate(null, candidateSpacingCells, candidatePriority);
        GerminationCandidate neighbor = new GerminationCandidate(null, 0, neighborPriority);
        Map<CoarseCell, GerminationCandidate> candidatesByCell = Map.of(origin, candidate, neighborCell, neighbor);
        return isLocalMaximum(origin, candidate, candidatesByCell);
    }

    private static double targetRadiusFromCandidate(GerminationCandidate candidate) {
        double sizeRangeBlocks = candidate.biome().sizeMaxBlocks() - candidate.biome().sizeMinBlocks();
        return candidate.biome().sizeMinBlocks() / 2.0 + candidate.priority() * sizeRangeBlocks / 2.0;
    }

    private static double computeCellScore(
        CoarseCell cell, GrowingPatch patch, Map<CoarseCell, ResourceKey<Biome>> resolvedCells, TerrainClass cellRealTerrainClass
    ) {
        double score = patch.biome.weight();
        // See GROWTH_BLEND_RELIANCE_SCORE_PENALTY's own comment -- claiming a cell whose
        // real terrain class this patch's biome doesn't actually declare (i.e. eligible
        // here only via the FLAT_LAND/HILLY blend) is disfavored relative to a same-class
        // competitor, not blocked outright.
        if (!patch.biome.terrainClasses().contains(cellRealTerrainClass)) {
            score -= GROWTH_BLEND_RELIANCE_SCORE_PENALTY;
        }
        for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
            ResourceKey<Biome> neighborBiome = resolvedCells.get(new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]));
            if (neighborBiome == null) {
                continue;
            }
            if (neighborBiome.equals(patch.biome.id())) {
                score += SAME_BIOME_COHESION_BONUS_PER_NEIGHBOR;
            }
            if (patch.biome.preferredNeighbors().contains(neighborBiome)) {
                score += PREFERRED_NEIGHBOR_SCORE_BONUS;
            }
            if (patch.biome.forbiddenNeighbors().contains(neighborBiome)) {
                score -= FORBIDDEN_NEIGHBOR_SCORE_PENALTY;
            }
        }
        // Deterministic tie-break unique per (cell, patch) -- makes real score ties
        // between competing patches vanishingly unlikely, so heap-pop order never
        // depends on push order. Keyed off patch.identitySeed (order-independent).
        long tieBreakSeed = PatchSeedHasher.cellSeed(patch.identitySeed, cell.cellX(), 0, cell.cellZ());
        return score + PatchSeedHasher.unitIntervalFromSeed(tieBreakSeed) * TIE_BREAK_SCORE_SCALE;
    }

    /** Test-support entry point (package-visible, not private): lets
     * PatchGrowthEngineSelfTest exercise computeCellScore's real scoring logic
     * (including GROWTH_BLEND_RELIANCE_SCORE_PENALTY) directly, without needing to
     * construct a full deterministic head-to-head contested-growth scenario -- real
     * best-first queue ordering and neighbor-bonus accumulation would make that far less
     * precise than checking the scoring primitive itself. Builds a throwaway
     * single-cell, no-resolved-neighbors patch purely for the score computation; not
     * used by any real growth path. */
    static double computeCellScoreForTest(CoarseCell cell, BiomeConfig biome, TerrainClass cellRealTerrainClass, long identitySeed) {
        GrowingPatch patch = new GrowingPatch(biome, 0, identitySeed, null);
        return computeCellScore(cell, patch, Map.of(), cellRealTerrainClass);
    }

    /** Approximate patch radius from assigned cell count, treating the patch as a disc.
     * Public: SurfacePatchGrid's debug tooling reuses this to report human-readable
     * patch sizes without duplicating the area/disc math. */
    public static double patchRadiusFromCellCount(int cellCount, double cellSizeBlocks) {
        double areaBlocksSquared = cellCount * (cellSizeBlocks * cellSizeBlocks);
        return Math.sqrt(areaBlocksSquared / Math.PI);
    }

    private static void pushEligibleNeighbors(
        PriorityQueue<FrontierEntry> frontier, AtomicLong pushCounter, CoarseCell cell, CoarseCell patchOrigin,
        GrowingPatch patch, Map<CoarseCell, ResourceKey<Biome>> resolvedCells,
        Map<CoarseCell, TerrainClass> terrainClassByCell, WindowBounds bounds
    ) {
        for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
            CoarseCell neighbor = new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]);
            if (!bounds.contains(neighbor)) {
                continue; // never grow outside the resolved window -- see WindowBounds' class comment
            }
            if (resolvedCells.containsKey(neighbor)) {
                continue;
            }
            TerrainClass neighborRealTerrainClass = terrainClassByCell.get(neighbor);
            if (!isEligibleForGrowth(patch.biome, neighborRealTerrainClass)) {
                continue;
            }
            double score = computeCellScore(neighbor, patch, resolvedCells, neighborRealTerrainClass);
            frontier.add(new FrontierEntry(-score, pushCounter.getAndIncrement(), neighbor, patchOrigin));
        }
    }

    /** Global best-first growth: heap pop order is a pure function of scores (themselves
     * seed-derived), never of push order -- see computeCellScore's tie-break comment. */
    private static void growUntilFrontierEmpty(
        PriorityQueue<FrontierEntry> frontier, AtomicLong pushCounter, Map<CoarseCell, GrowingPatch> patchesByCell,
        Map<CoarseCell, ResourceKey<Biome>> resolvedCells, Map<CoarseCell, RegionFamily> regionFamilyByCell,
        Map<CoarseCell, TerrainClass> terrainClassByCell, WindowBounds bounds, double cellSizeBlocks
    ) {
        while (!frontier.isEmpty()) {
            FrontierEntry entry = frontier.poll();
            if (resolvedCells.containsKey(entry.cell())) {
                continue; // a different patch already claimed this cell
            }
            GrowingPatch patch = patchesByCell.get(entry.patchOrigin());
            double prospectiveRadius = patchRadiusFromCellCount(patch.cellCount + 1, cellSizeBlocks);
            if (prospectiveRadius > patch.targetRadiusBlocks) {
                continue; // this patch has already reached its sampled target size
            }
            resolvedCells.put(entry.cell(), patch.biome.id());
            if (patch.regionFamily != null) {
                regionFamilyByCell.put(entry.cell(), patch.regionFamily);
            }
            patch.cellCount++;
            pushEligibleNeighbors(frontier, pushCounter, entry.cell(), entry.patchOrigin(), patch, resolvedCells, terrainClassByCell, bounds);
        }
    }

    /** Post-processing pass: finds contiguous same-biome components too small to be a
     * real patch and reassigns them to whichever bordering biome is both dominant and
     * terrain-class-eligible for every cell involved. See the class-level constant's
     * comment for why this exists. Mutates resolvedCells in place.
     *
     * All decisions read from a frozen snapshot taken before any reassignment, and every
     * reassignment is applied only after every component has been decided -- otherwise
     * two adjacent small islands' border tallies would depend on which one got processed
     * first, breaking this class's order-independence guarantee (see class comment). */
    private static void dissolveSmallIslands(
        Map<CoarseCell, ResourceKey<Biome>> resolvedCells, Map<CoarseCell, RegionFamily> regionFamilyByCell,
        Map<CoarseCell, TerrainClass> terrainClassByCell, List<BiomeConfig> biomes, double cellSizeBlocks
    ) {
        Map<ResourceKey<Biome>, BiomeConfig> configById = new HashMap<>();
        for (BiomeConfig biome : biomes) {
            configById.put(biome.id(), biome);
        }

        Map<CoarseCell, ResourceKey<Biome>> snapshot = new HashMap<>(resolvedCells);
        // Frozen alongside snapshot for the same reason (see this method's class-level
        // doc): a dissolved island's replacement family must read pre-reassignment state,
        // not whatever another island's dissolve happened to write moments earlier.
        Map<CoarseCell, RegionFamily> regionFamilySnapshot = new HashMap<>(regionFamilyByCell);
        Map<CoarseCell, ResourceKey<Biome>> biomeReassignments = new HashMap<>();
        Map<CoarseCell, RegionFamily> familyReassignments = new HashMap<>();
        Set<CoarseCell> visited = new HashSet<>();
        for (CoarseCell cell : snapshot.keySet()) {
            if (visited.contains(cell)) {
                continue;
            }
            ResourceKey<Biome> biome = snapshot.get(cell);
            List<CoarseCell> component = floodFillSameBiomeComponent(cell, biome, snapshot, visited);

            BiomeConfig biomeConfig = configById.get(biome);
            if (biomeConfig == null || component.size() >= smallIslandThreshold(biomeConfig, cellSizeBlocks)) {
                continue;
            }
            ResourceKey<Biome> replacement = pickDissolveReplacement(component, biome, snapshot, terrainClassByCell, configById);
            if (replacement != null) {
                RegionFamily replacementFamily = pickDissolveReplacementFamily(component, replacement, snapshot, regionFamilySnapshot);
                for (CoarseCell componentCell : component) {
                    biomeReassignments.put(componentCell, replacement);
                    familyReassignments.put(componentCell, replacementFamily);
                }
            }
        }
        resolvedCells.putAll(biomeReassignments);
        // A dissolved cell's OLD family (from the biome it stopped being) must never
        // survive the reassignment -- put the new one, or remove entirely if the
        // replacement biome had no bordering family info to inherit (null value, not a
        // missing key, is not the "stay reactive" contract elsewhere in this file).
        for (Map.Entry<CoarseCell, RegionFamily> entry : familyReassignments.entrySet()) {
            if (entry.getValue() != null) {
                regionFamilyByCell.put(entry.getKey(), entry.getValue());
            } else {
                regionFamilyByCell.remove(entry.getKey());
            }
        }
    }

    /** Majority family among the component's border cells that already share the winning
     * replacement biome (snapshot state, pre-reassignment) -- null if none of them had a
     * family (e.g. replacement itself never rolls one). */
    private static RegionFamily pickDissolveReplacementFamily(
        List<CoarseCell> component, ResourceKey<Biome> replacement,
        Map<CoarseCell, ResourceKey<Biome>> resolvedCells, Map<CoarseCell, RegionFamily> regionFamilyByCell
    ) {
        Set<CoarseCell> componentCells = new HashSet<>(component);
        Map<RegionFamily, Integer> familyCounts = new HashMap<>();
        for (CoarseCell cell : component) {
            for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
                CoarseCell neighbor = new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]);
                if (componentCells.contains(neighbor) || !replacement.equals(resolvedCells.get(neighbor))) {
                    continue;
                }
                RegionFamily family = regionFamilyByCell.get(neighbor);
                if (family != null) {
                    familyCounts.merge(family, 1, Integer::sum);
                }
            }
        }
        return familyCounts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    private static List<CoarseCell> floodFillSameBiomeComponent(
        CoarseCell start, ResourceKey<Biome> biome, Map<CoarseCell, ResourceKey<Biome>> resolvedCells, Set<CoarseCell> visited
    ) {
        List<CoarseCell> component = new ArrayList<>();
        Deque<CoarseCell> frontier = new ArrayDeque<>();
        visited.add(start);
        frontier.push(start);
        while (!frontier.isEmpty()) {
            CoarseCell cell = frontier.pop();
            component.add(cell);
            for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
                CoarseCell neighbor = new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]);
                if (visited.contains(neighbor) || !biome.equals(resolvedCells.get(neighbor))) {
                    continue;
                }
                visited.add(neighbor);
                frontier.push(neighbor);
            }
        }
        return component;
    }

    /** A component is "too small" if it's a meaningful fraction below what even the
     * smallest legitimate roll of its own biome's [size_min, size_max] range would
     * produce -- see the class-level SMALL_ISLAND_DISSOLVE_FRACTION comment. */
    private static int smallIslandThreshold(BiomeConfig biome, double cellSizeBlocks) {
        double minRadiusCells = biome.sizeMinBlocks() / 2.0 / cellSizeBlocks;
        double expectedMinCells = Math.PI * minRadiusCells * minRadiusCells;
        return (int) Math.max(1, Math.round(expectedMinCells * SMALL_ISLAND_DISSOLVE_FRACTION));
    }

    private static ResourceKey<Biome> pickDissolveReplacement(
        List<CoarseCell> component, ResourceKey<Biome> originalBiome, Map<CoarseCell, ResourceKey<Biome>> resolvedCells,
        Map<CoarseCell, TerrainClass> terrainClassByCell, Map<ResourceKey<Biome>, BiomeConfig> configById
    ) {
        Set<CoarseCell> componentCells = new HashSet<>(component);
        Map<ResourceKey<Biome>, Integer> borderCounts = new HashMap<>();
        for (CoarseCell cell : component) {
            for (int[] offset : CARDINAL_NEIGHBOR_OFFSETS) {
                CoarseCell neighbor = new CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]);
                if (componentCells.contains(neighbor)) {
                    continue;
                }
                ResourceKey<Biome> neighborBiome = resolvedCells.get(neighbor);
                if (neighborBiome == null || neighborBiome.equals(originalBiome)) {
                    continue;
                }
                borderCounts.merge(neighborBiome, 1, Integer::sum);
            }
        }

        return borderCounts.entrySet().stream()
            .sorted(Map.Entry.<ResourceKey<Biome>, Integer>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .filter(candidate -> isEligibleForEveryCell(candidate, component, terrainClassByCell, configById))
            .findFirst()
            .orElse(null);
    }

    private static boolean isEligibleForEveryCell(
        ResourceKey<Biome> candidate, List<CoarseCell> component,
        Map<CoarseCell, TerrainClass> terrainClassByCell, Map<ResourceKey<Biome>, BiomeConfig> configById
    ) {
        // Absorbing a dissolved island's cells is an extension of the replacement
        // biome's existing territory, not a fresh germination -- growth's (permissive)
        // rule applies, matching pushEligibleNeighbors' own use of it during normal growth.
        BiomeConfig candidateConfig = configById.get(candidate);
        if (candidateConfig == null) {
            return false;
        }
        for (CoarseCell cell : component) {
            if (!isEligibleForGrowth(candidateConfig, terrainClassByCell.get(cell))) {
                return false;
            }
        }
        return true;
    }
}
