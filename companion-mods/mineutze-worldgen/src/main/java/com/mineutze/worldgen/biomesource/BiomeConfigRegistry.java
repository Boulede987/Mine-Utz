package com.mineutze.worldgen.biomesource;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Set;

/**
 * Holds the parsed runtime biome placement config CustomOverworldBiomeSource reads.
 *
 * TEMPORARY placeholder -- see the plan's rollout section: hardcodes a small handful of
 * vanilla biomes with real, differentiated size_min/size_max (Milestone 2), real,
 * differentiated terrain_classes (Milestone 3), and now real preferred/forbidden
 * neighbor lists (Milestone 4). The neighbor scoring itself
 * (PatchGrowthEngine.computeCellScore) already existed since Milestone 1 -- it just had
 * nothing but empty lists to read, so it was a no-op until now. This is soft adjacency
 * (a growth-priority bonus/penalty), not a hard block -- forbidden neighbors are
 * strongly discouraged, not physically impossible, matching the user's explicit choice
 * over a hard-block design (see the plan's Context section).
 * Replace loadPlaceholderConfig() with a real datapack JSON reload listener
 * (patch_config.json, see the plan's "Runtime config delivery" section) once that
 * authoring pipeline exists -- do not hand-tune this list further, it's scaffolding.
 *
 * Milestone 5 adds two underground-only entries (DRIPSTONE_CAVES, LUSH_CAVES) with a
 * non-empty undergroundLinkedSurfaceBiomes list and an empty terrainClasses set --
 * empty terrainClasses means they can never win a surface cell through the normal
 * PatchGrowthEngine gate, which is correct: they're placed entirely through
 * UndergroundPatchGrid's separate column-linkage mechanism instead (see its class
 * comment). sizeMinBlocks/sizeMaxBlocks are unused for these two -- underground biomes
 * don't grow in this milestone (requirement 3 only asked for linkage, not underground
 * patch size/adjacency) -- left at 0 rather than a misleading nonzero placeholder.
 */
public final class BiomeConfigRegistry {

    private static final ResourceKey<Biome> PLAINS           = biomeKey("minecraft", "plains");
    private static final ResourceKey<Biome> DESERT           = biomeKey("minecraft", "desert");
    private static final ResourceKey<Biome> SNOWY_PLAINS     = biomeKey("minecraft", "snowy_plains");
    private static final ResourceKey<Biome> SAVANNA          = biomeKey("minecraft", "savanna");
    private static final ResourceKey<Biome> FOREST           = biomeKey("minecraft", "forest");
    private static final ResourceKey<Biome> OCEAN            = biomeKey("minecraft", "ocean");
    private static final ResourceKey<Biome> WINDSWEPT_HILLS  = biomeKey("minecraft", "windswept_hills");
    private static final ResourceKey<Biome> DRIPSTONE_CAVES  = biomeKey("minecraft", "dripstone_caves");
    private static final ResourceKey<Biome> LUSH_CAVES        = biomeKey("minecraft", "lush_caves");
    private static final ResourceKey<Biome> RIVER             = biomeKey("minecraft", "river");

    private static final List<BiomeConfig> CONFIGS = loadPlaceholderConfig();

    /** Largest size_max across every configured biome -- SurfacePatchGrid sizes its
     * resolution window off this so patches aren't routinely truncated by the window
     * edge instead of their real termination rule. */
    public static final int MAX_SIZE_MAX_BLOCKS = CONFIGS.stream().mapToInt(BiomeConfig::sizeMaxBlocks).max().orElse(0);

    private BiomeConfigRegistry() {
    }

    public static List<BiomeConfig> allConfigs() {
        return CONFIGS;
    }

    private static List<BiomeConfig> loadPlaceholderConfig() {
        return List.of(
            new BiomeConfig(
                PLAINS, Set.of(TerrainClass.FLAT_LAND, TerrainClass.COAST), 400, 900, 1.0f,
                List.of(FOREST, SAVANNA), List.of(), List.of()
            ),
            new BiomeConfig(
                DESERT, Set.of(TerrainClass.FLAT_LAND, TerrainClass.COAST), 900, 1900, 0.8f,
                List.of(SAVANNA), List.of(SNOWY_PLAINS), List.of()
            ),
            new BiomeConfig(
                SNOWY_PLAINS, Set.of(TerrainClass.FLAT_LAND), 300, 600, 0.8f,
                List.of(), List.of(DESERT, SAVANNA), List.of()
            ),
            new BiomeConfig(
                SAVANNA, Set.of(TerrainClass.FLAT_LAND, TerrainClass.COAST), 350, 700, 0.8f,
                List.of(DESERT, PLAINS), List.of(SNOWY_PLAINS), List.of()
            ),
            new BiomeConfig(
                FOREST, Set.of(TerrainClass.FLAT_LAND, TerrainClass.HILLY), 250, 550, 1.0f,
                List.of(PLAINS, WINDSWEPT_HILLS), List.of(), List.of()
            ),
            new BiomeConfig(
                OCEAN, Set.of(TerrainClass.OCEAN), 500, 1000, 1.0f,
                List.of(), List.of(), List.of()
            ),
            new BiomeConfig(
                WINDSWEPT_HILLS, Set.of(TerrainClass.HILLY, TerrainClass.MOUNTAINOUS), 200, 450, 0.6f,
                List.of(FOREST), List.of(), List.of()
            ),
            new BiomeConfig(
                RIVER, Set.of(TerrainClass.RIVER), 200, 1000, 1.0f,
                List.of(), List.of(), List.of()
            ),
            new BiomeConfig(
                DRIPSTONE_CAVES, Set.of(), 0, 0, 1.0f,
                List.of(), List.of(), List.of(WINDSWEPT_HILLS, DESERT)
            ),
            new BiomeConfig(
                LUSH_CAVES, Set.of(), 0, 0, 1.0f,
                List.of(), List.of(), List.of(FOREST, PLAINS)
            )
        );
    }

    private static ResourceKey<Biome> biomeKey(String namespace, String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
