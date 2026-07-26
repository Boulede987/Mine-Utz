package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.BiomeConfig;
import com.mineutze.worldgen.biomesource.RegionFamily;
import com.mineutze.worldgen.biomesource.RegionFamilyWeight;
import com.mineutze.worldgen.biomesource.TerrainClass;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Plain-Java algorithm self-test for PatchGrowthEngine -- runnable via `./gradlew
 * selfTest` in a few seconds, no deployMod, no game launch. Replaces the old Python
 * patch_engine.py prototype's determinism self-test: that port's stated justification
 * ("iterating here is cheaper than a Gradle rebuild per tweak") didn't hold up once it
 * became clear `compileJava` alone is fast, and hand-porting every algorithm fix to two
 * languages (the patch-identity determinism bug, the dissolve-small-islands snapshot
 * fix) was real, ongoing maintenance cost for a benefit nobody was using.
 *
 * PatchGrowthEngine.resolveWindow has no external "scan order" parameter the way the
 * Python prototype's resolve_region did -- its internal iteration order is always fixed.
 * So the property actually worth checking here isn't "does scan order change the
 * result" (there's no scan order to vary), it's the more fundamental one that property
 * depends on: resolveWindow is a pure function of its arguments with no shared mutable
 * state, so calling it repeatedly -- including concurrently, from different threads,
 * the way NeoForge's parallel chunk generation actually calls into SurfacePatchGrid --
 * must always return an identical result. This test proves that directly instead of
 * simulating discovery orders that don't exist in the real API anymore.
 */
public final class PatchGrowthEngineSelfTest {

    private static final long WORLD_SEED = 12345L;
    private static final double CELL_SIZE_BLOCKS = 16.0;
    private static final int WINDOW_RADIUS_CELLS = 24;
    private static final long TERRAIN_GRID_SEED = 7L;
    private static final int CONCURRENT_RUN_COUNT = 8;

    private PatchGrowthEngineSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean determinismPassed = runDeterminismSelfTest();
        System.out.println("Determinism self-test: " + (determinismPassed ? "PASS" : "FAIL"));

        boolean terrainBlindnessPassed = runTerrainBlindnessSelfTest();
        System.out.println("Terrain-blindness self-test: " + (terrainBlindnessPassed ? "PASS" : "FAIL"));

        boolean dissolveFamilyPassed = runDissolveFamilySelfTest();
        System.out.println("Dissolve-family self-test: " + (dissolveFamilyPassed ? "PASS" : "FAIL"));

        boolean blendPenaltyPassed = runGrowthBlendPenaltySelfTest();
        System.out.println("Growth-blend-penalty self-test: " + (blendPenaltyPassed ? "PASS" : "FAIL"));

        boolean spacingScalesWithMaxSizePassed = runSpacingScalesWithMaxSizeSelfTest();
        System.out.println("Spacing-scales-with-max-size self-test: " + (spacingScalesWithMaxSizePassed ? "PASS" : "FAIL"));

        if (!determinismPassed || !terrainBlindnessPassed || !dissolveFamilyPassed
            || !blendPenaltyPassed || !spacingScalesWithMaxSizePassed) {
            System.exit(1);
        }
    }

    static boolean runDeterminismSelfTest() throws Exception {
        List<BiomeConfig> biomes = buildTestBiomes();
        PatchGrowthEngine.TerrainSampler terrainSampler = PatchGrowthEngineSelfTest::classifySyntheticCell;

        PatchGrowthEngine.WindowResolution baseline = PatchGrowthEngine.resolveWindow(
            WORLD_SEED, biomes, CELL_SIZE_BLOCKS, terrainSampler,
            -WINDOW_RADIUS_CELLS, -WINDOW_RADIUS_CELLS, WINDOW_RADIUS_CELLS + 1, WINDOW_RADIUS_CELLS + 1
        );

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_RUN_COUNT);
        try {
            List<Callable<PatchGrowthEngine.WindowResolution>> tasks = java.util.stream.IntStream
                .range(0, CONCURRENT_RUN_COUNT)
                .<Callable<PatchGrowthEngine.WindowResolution>>mapToObj(index -> () -> PatchGrowthEngine.resolveWindow(
                    WORLD_SEED, biomes, CELL_SIZE_BLOCKS, terrainSampler,
                    -WINDOW_RADIUS_CELLS, -WINDOW_RADIUS_CELLS, WINDOW_RADIUS_CELLS + 1, WINDOW_RADIUS_CELLS + 1
                ))
                .toList();

            for (Future<PatchGrowthEngine.WindowResolution> future : executor.invokeAll(tasks)) {
                if (!future.get().equals(baseline)) {
                    return false;
                }
            }
        } finally {
            executor.shutdown();
        }
        return true;
    }

    /** Fork 1, THREE TIMES revised after real in-game evidence. First revision: split
     * germination (strict, exact real-class match, unchanged from Phase 1) from growth
     * (an already-germinated patch may SPREAD onto adjacent land regardless of its real
     * class) -- germination-time land-blindness was letting biomes spawn anywhere on any
     * land cell, collapsing 3 real terrain classes' natural clustering into noise.
     * Second revision: growth's land-blindness itself was still too broad -- a real
     * desert patch (declares only FLAT_LAND/COAST) grew directly onto a real MOUNTAINOUS
     * cell across an actual coast-to-mountain transition, confirmed via
     * /dump_terrain_class showing smooth, non-noisy real classification either side (so
     * not a classifier-noise problem). MOUNTAINOUS excluded from growth's blend too --
     * only FLAT_LAND/HILLY blend during growth, MOUNTAINOUS requires an exact match same
     * as water. Third revision, this one: growth's FLAT_LAND/HILLY blend was itself
     * UNCONDITIONALLY permissive (any biome declaring either class could invade cells of
     * either, on equal footing with a biome that actually declares that exact class) --
     * real symptom: windswept_hills/plains and windswept_hills/forest boundary
     * patchwork. Fixed with GROWTH_BLEND_RELIANCE_SCORE_PENALTY (score-based, not an
     * eligibility change -- see PatchGrowthEngine's own comment on why an eligibility
     * fix would have silently disabled the blend entirely). That penalty makes
     * blend-reliant growth DISFAVORED, not blocked -- so a HILLY-only biome now
     * correctly prefers staying within same-class HILLY territory as long as any is
     * still available, and only spills into FLAT_LAND once its own native territory
     * can't satisfy its target radius alone. The HILLY region below is deliberately a
     * SINGLE cell (not the original long column -- a long column gave hillyOnly enough
     * same-class room along its own length to satisfy a modest target radius without
     * ever needing FLAT_LAND, making spillover no longer a hard guarantee and silently
     * breaking this test after the penalty was introduced) specifically so spillover
     * into FLAT_LAND is REQUIRED regardless of the exact size/penalty tuning, not just
     * likely -- (a) hilly-only MUST spread beyond its own single native cell onto
     * FLAT_LAND (nowhere else for it to grow at all), (b) mountainous-only (kept as a
     * full column -- doesn't need to spread, this assertion is about NEVER leaving,
     * unaffected by native-room size) must NEVER appear outside its own column (growth
     * no longer crosses onto/off MOUNTAINOUS), and (c) ocean-only (same, full column)
     * must NEVER appear outside its own column (water stays gated, unchanged). */
    static boolean runTerrainBlindnessSelfTest() {
        ResourceKey<Biome> hillyOnly = testBiomeKey("hilly_only");
        ResourceKey<Biome> mountainousOnly = testBiomeKey("mountainous_only");
        ResourceKey<Biome> oceanOnly = testBiomeKey("ocean_only");
        List<BiomeConfig> biomes = List.of(
            new BiomeConfig(hillyOnly, Set.of(TerrainClass.HILLY), 64, 128, 1.0f, List.of(), List.of(), List.of(), List.of()),
            new BiomeConfig(mountainousOnly, Set.of(TerrainClass.MOUNTAINOUS), 64, 128, 1.0f, List.of(), List.of(), List.of(), List.of()),
            new BiomeConfig(oceanOnly, Set.of(TerrainClass.OCEAN), 64, 128, 1.0f, List.of(), List.of(), List.of(), List.of())
        );
        // hillyOnly's native terrain is ONLY the single cell (HILLY_ORIGIN_CELL_X, 0) --
        // see the method doc for why a single cell, not a column, is what makes
        // spillover a hard guarantee under the growth-blend-penalty fix. Placed far from
        // columns 1/2 (Chebyshev distance > this test's spacingRadiusInCells, which is
        // sizeMax=128-driven i.e. 4 cells -- see PatchGrowthEngine.spacingRadiusInCells)
        // so the single hillyOnly germination candidate is never cross-biome
        // local-maximum-compared against a mountainousOnly/oceanOnly candidate and
        // spuriously suppressed before it can even germinate -- a real failure mode
        // hit while developing this test, not a hypothetical. mountainous/ocean stay
        // full columns since their assertions are about never spreading, not about
        // being forced to.
        int hillyOriginCellX = -10;
        PatchGrowthEngine.TerrainSampler terrainSampler = (cellX, cellZ) -> {
            if (cellX == hillyOriginCellX && cellZ == 0) return TerrainClass.HILLY;
            if (cellX == 1) return TerrainClass.MOUNTAINOUS;
            if (cellX == 2) return TerrainClass.OCEAN;
            return TerrainClass.FLAT_LAND;
        };

        PatchGrowthEngine.WindowResolution resolution = PatchGrowthEngine.resolveWindow(
            WORLD_SEED, biomes, CELL_SIZE_BLOCKS, terrainSampler,
            -WINDOW_RADIUS_CELLS, -WINDOW_RADIUS_CELLS, WINDOW_RADIUS_CELLS + 1, WINDOW_RADIUS_CELLS + 1
        );

        boolean hillySpreadOntoFlatLand = resolution.biomeByCell().entrySet().stream()
            .anyMatch(entry -> hillyOnly.equals(entry.getValue()) && !(entry.getKey().cellX() == hillyOriginCellX && entry.getKey().cellZ() == 0));
        boolean mountainousNeverLeftItsOwnColumn = resolution.biomeByCell().entrySet().stream()
            .noneMatch(entry -> mountainousOnly.equals(entry.getValue()) && entry.getKey().cellX() != 1);
        boolean oceanNeverLeftItsOwnColumn = resolution.biomeByCell().entrySet().stream()
            .noneMatch(entry -> oceanOnly.equals(entry.getValue()) && entry.getKey().cellX() != 2);
        return hillySpreadOntoFlatLand && mountainousNeverLeftItsOwnColumn && oceanNeverLeftItsOwnColumn;
    }

    // Parameters chosen so a lone "tiny" germination point is likely to get boxed in by
    // "dominant" (weight 10x higher) at 1-2 cells, well under tiny's own dissolve
    // threshold -- see the method comment for the exact reasoning. Own constants, not
    // shared with the determinism test's window, since this test wants a larger sample
    // (more candidate cells) to reliably produce at least one real dissolve event.
    private static final int DISSOLVE_TEST_WINDOW_RADIUS_CELLS = 48;

    /** Fork "dissolveSmallIslands must also reassign family, not just biome" (plan Sec
     * "PatchGrowthEngineSelfTest coverage"): "dominant" and "tiny" each declare exactly
     * ONE regionFamilyWeights entry, so pickWeightedRegionFamily is fully deterministic
     * per biome regardless of seed -- every cell resolveWindow ever assigns to
     * "dominant" MUST show dominant's own family, CLUB_WEAK, never tiny's HEART. This
     * holds whether or not a real dissolve happens to occur for this seed/window (a
     * correct implementation never fails it), but specifically catches the regression
     * this exists to guard against: a dissolved tiny-island cell reassigned to dominant
     * in biomeByCell while still carrying tiny's stale HEART in regionFamilyByCell. */
    static boolean runDissolveFamilySelfTest() {
        ResourceKey<Biome> dominant = testBiomeKey("dissolve_dominant");
        ResourceKey<Biome> tiny = testBiomeKey("dissolve_tiny");
        List<RegionFamilyWeight> dominantFamily = List.of(new RegionFamilyWeight(RegionFamily.CLUB_WEAK, 1.0f));
        List<RegionFamilyWeight> tinyFamily = List.of(new RegionFamilyWeight(RegionFamily.HEART, 1.0f));
        List<BiomeConfig> biomes = List.of(
            new BiomeConfig(dominant, Set.of(TerrainClass.FLAT_LAND), 64, 128, 30.0f, List.of(), List.of(), List.of(), dominantFamily),
            new BiomeConfig(tiny, Set.of(TerrainClass.FLAT_LAND), 48, 96, 3.0f, List.of(), List.of(), List.of(), tinyFamily)
        );
        PatchGrowthEngine.TerrainSampler allFlatLand = (cellX, cellZ) -> TerrainClass.FLAT_LAND;

        PatchGrowthEngine.WindowResolution resolution = PatchGrowthEngine.resolveWindow(
            WORLD_SEED, biomes, CELL_SIZE_BLOCKS, allFlatLand,
            -DISSOLVE_TEST_WINDOW_RADIUS_CELLS, -DISSOLVE_TEST_WINDOW_RADIUS_CELLS,
            DISSOLVE_TEST_WINDOW_RADIUS_CELLS + 1, DISSOLVE_TEST_WINDOW_RADIUS_CELLS + 1
        );

        for (Map.Entry<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>> entry : resolution.biomeByCell().entrySet()) {
            if (dominant.equals(entry.getValue()) && resolution.regionFamilyByCell().get(entry.getKey()) != RegionFamily.CLUB_WEAK) {
                return false;
            }
        }
        return true;
    }

    // Margin the growth-blend penalty must clear to count as "applied" in this test --
    // well above the ~1e-6 tie-break scale (real score noise), well below the penalty's
    // real configured magnitude (0.5 as of this writing) -- deliberately NOT a duplicate
    // of PatchGrowthEngine's own private constant, so retuning that value doesn't also
    // require touching this test; this only asserts the mechanism still fires in the
    // right direction, not its exact magnitude.
    private static final double MIN_EXPECTED_BLEND_PENALTY_MARGIN = 0.3;

    /** Fork "Cause A: FLAT_LAND/HILLY growth blend was unconditionally permissive"
     * (real in-game symptom: windswept_hills/plains and windswept_hills/forest boundary
     * patchwork, since ANY biome declaring either blend class could invade ANY cell of
     * either class on equal footing with a biome that actually declares it). Fix was a
     * score penalty (GROWTH_BLEND_RELIANCE_SCORE_PENALTY), not an eligibility change --
     * blend-reliant growth stays POSSIBLE (preserves Stage 3/4's territory-expansion
     * purpose) but scores below a same-class claim. Verifies via computeCellScoreForTest
     * directly (same cell, same identitySeed, so the tie-break term is identical between
     * both calls and no neighbor bonuses apply with an empty resolvedCells map) rather
     * than simulating a full contested-growth scenario, which would be far less precise
     * given real best-first queue ordering and neighbor-bonus accumulation. */
    static boolean runGrowthBlendPenaltySelfTest() {
        ResourceKey<Biome> flatOnly = testBiomeKey("blend_penalty_flat_only");
        BiomeConfig flatOnlyConfig = new BiomeConfig(
            flatOnly, Set.of(TerrainClass.FLAT_LAND), 64, 128, 1.0f, List.of(), List.of(), List.of(), List.of()
        );
        PatchGrowthEngine.CoarseCell cell = new PatchGrowthEngine.CoarseCell(0, 0);
        long identitySeed = 42L;

        double scoreOnDeclaredClass = PatchGrowthEngine.computeCellScoreForTest(cell, flatOnlyConfig, TerrainClass.FLAT_LAND, identitySeed);
        double scoreOnBlendOnlyClass = PatchGrowthEngine.computeCellScoreForTest(cell, flatOnlyConfig, TerrainClass.HILLY, identitySeed);

        return scoreOnDeclaredClass - scoreOnBlendOnlyClass >= MIN_EXPECTED_BLEND_PENALTY_MARGIN;
    }

    /** Fork "Cause B: germination spacing must scale with sizeMaxBlocks, not
     * sizeMinBlocks" -- real in-game symptom: multiple biomes sharing one terrain class
     * (desert/savanna/plains/snowy_plains, all FLAT_LAND) fragmenting each other into
     * many small patches, because germination-time spacing only ever guaranteed
     * separation for a patch's SMALLEST possible size while growth can reach its
     * largest. Directly asserts the fix rather than reverse-engineering it from
     * germination-origin positions (not exposed by WindowResolution): holding
     * sizeMinBlocks fixed, spacing must strictly increase as sizeMaxBlocks increases --
     * this would NOT hold under the old sizeMinBlocks-based formula, which was blind to
     * sizeMaxBlocks entirely. */
    static boolean runSpacingScalesWithMaxSizeSelfTest() {
        ResourceKey<Biome> narrowRange = testBiomeKey("spacing_narrow_range");
        ResourceKey<Biome> wideRange = testBiomeKey("spacing_wide_range");
        BiomeConfig narrowRangeConfig = new BiomeConfig(
            narrowRange, Set.of(TerrainClass.FLAT_LAND), 900, 950, 1.0f, List.of(), List.of(), List.of(), List.of()
        );
        BiomeConfig wideRangeConfig = new BiomeConfig(
            wideRange, Set.of(TerrainClass.FLAT_LAND), 900, 1900, 1.0f, List.of(), List.of(), List.of(), List.of()
        );

        int narrowRangeSpacing = PatchGrowthEngine.spacingRadiusInCellsForTest(narrowRangeConfig, CELL_SIZE_BLOCKS);
        int wideRangeSpacing = PatchGrowthEngine.spacingRadiusInCellsForTest(wideRangeConfig, CELL_SIZE_BLOCKS);

        return wideRangeSpacing > narrowRangeSpacing;
    }

    /** Deterministic synthetic terrain, no Climate.Sampler needed -- reuses
     * PatchSeedHasher so this test has no dependency beyond this mod's own code. */
    private static TerrainClass classifySyntheticCell(int cellX, int cellZ) {
        long seed = PatchSeedHasher.cellSeed(TERRAIN_GRID_SEED, cellX, 0, cellZ);
        TerrainClass[] values = TerrainClass.values();
        int index = (int) (Math.floorMod(seed, (long) values.length));
        return values[index];
    }

    private static List<BiomeConfig> buildTestBiomes() {
        ResourceKey<Biome> plains = testBiomeKey("plains");
        ResourceKey<Biome> hills  = testBiomeKey("hills");
        ResourceKey<Biome> peaks  = testBiomeKey("peaks");
        ResourceKey<Biome> desert = testBiomeKey("desert");
        ResourceKey<Biome> beach  = testBiomeKey("beach");
        ResourceKey<Biome> sea    = testBiomeKey("sea");

        // plains/hills given non-trivial regionFamilyWeights so the determinism test
        // above actually exercises the family-roll code path, not just an empty list
        // everywhere -- see the plan's "PatchGrowthEngineSelfTest coverage" section.
        List<RegionFamilyWeight> plainsFamilies = List.of(new RegionFamilyWeight(RegionFamily.CLUB_WEAK, 1.0f));
        List<RegionFamilyWeight> hillsFamilies = List.of(
            new RegionFamilyWeight(RegionFamily.SPADE, 0.5f),
            new RegionFamilyWeight(RegionFamily.DIAMOND, 0.5f)
        );

        return List.of(
            new BiomeConfig(plains, Set.of(TerrainClass.FLAT_LAND), 48, 160, 1.0f, List.of(), List.of(), List.of(), plainsFamilies),
            new BiomeConfig(hills, Set.of(TerrainClass.HILLY), 32, 96, 1.0f, List.of(plains), List.of(), List.of(), hillsFamilies),
            new BiomeConfig(peaks, Set.of(TerrainClass.MOUNTAINOUS), 32, 128, 0.6f, List.of(), List.of(desert), List.of(), List.of()),
            new BiomeConfig(desert, Set.of(TerrainClass.FLAT_LAND, TerrainClass.COAST), 48, 160, 0.8f, List.of(), List.of(peaks), List.of(), List.of()),
            new BiomeConfig(beach, Set.of(TerrainClass.COAST), 16, 48, 1.0f, List.of(), List.of(), List.of(), List.of()),
            new BiomeConfig(sea, Set.of(TerrainClass.OCEAN), 64, 200, 1.0f, List.of(), List.of(), List.of(), List.of())
        );
    }

    private static ResourceKey<Biome> testBiomeKey(String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("selftest", path));
    }
}
