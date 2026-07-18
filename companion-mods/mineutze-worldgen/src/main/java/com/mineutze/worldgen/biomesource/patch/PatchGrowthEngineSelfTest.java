package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.BiomeConfig;
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
        boolean passed = runDeterminismSelfTest();
        System.out.println("Determinism self-test: " + (passed ? "PASS" : "FAIL"));
        if (!passed) {
            System.exit(1);
        }
    }

    static boolean runDeterminismSelfTest() throws Exception {
        List<BiomeConfig> biomes = buildTestBiomes();
        PatchGrowthEngine.TerrainSampler terrainSampler = PatchGrowthEngineSelfTest::classifySyntheticCell;

        Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>> baseline = PatchGrowthEngine.resolveWindow(
            WORLD_SEED, biomes, CELL_SIZE_BLOCKS, terrainSampler,
            -WINDOW_RADIUS_CELLS, -WINDOW_RADIUS_CELLS, WINDOW_RADIUS_CELLS + 1, WINDOW_RADIUS_CELLS + 1
        );

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_RUN_COUNT);
        try {
            List<Callable<Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>>>> tasks = java.util.stream.IntStream
                .range(0, CONCURRENT_RUN_COUNT)
                .<Callable<Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>>>>mapToObj(index -> () -> PatchGrowthEngine.resolveWindow(
                    WORLD_SEED, biomes, CELL_SIZE_BLOCKS, terrainSampler,
                    -WINDOW_RADIUS_CELLS, -WINDOW_RADIUS_CELLS, WINDOW_RADIUS_CELLS + 1, WINDOW_RADIUS_CELLS + 1
                ))
                .toList();

            for (Future<Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>>> future : executor.invokeAll(tasks)) {
                if (!future.get().equals(baseline)) {
                    return false;
                }
            }
        } finally {
            executor.shutdown();
        }
        return true;
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

        return List.of(
            new BiomeConfig(plains, Set.of(TerrainClass.FLAT_LAND), 48, 160, 1.0f, List.of(), List.of(), List.of()),
            new BiomeConfig(hills, Set.of(TerrainClass.HILLY), 32, 96, 1.0f, List.of(plains), List.of(), List.of()),
            new BiomeConfig(peaks, Set.of(TerrainClass.MOUNTAINOUS), 32, 128, 0.6f, List.of(), List.of(desert), List.of()),
            new BiomeConfig(desert, Set.of(TerrainClass.FLAT_LAND, TerrainClass.COAST), 48, 160, 0.8f, List.of(), List.of(peaks), List.of()),
            new BiomeConfig(beach, Set.of(TerrainClass.COAST), 16, 48, 1.0f, List.of(), List.of(), List.of()),
            new BiomeConfig(sea, Set.of(TerrainClass.OCEAN), 64, 200, 1.0f, List.of(), List.of(), List.of())
        );
    }

    private static ResourceKey<Biome> testBiomeKey(String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("selftest", path));
    }
}
