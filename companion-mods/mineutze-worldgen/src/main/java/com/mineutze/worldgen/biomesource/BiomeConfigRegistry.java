package com.mineutze.worldgen.biomesource;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

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

    // Phase 2 (TERRAIN_COHERENCE_DESIGN.md Sec 4b/4c) shape-intent weights -- provisional,
    // hand-authored starting content, explicitly NOT derivable from data (Sec 4c: which
    // Tectonic family "feels right" for a biome is aesthetic judgment). Expect to retune
    // after seeing real generated terrain (Sec 4c's own "inherently iterative" framing) --
    // do not treat these numbers as settled. Empty for OCEAN/RIVER/underground biomes:
    // those stay reactive to real Tectonic terrain rather than declaring shape intent
    // (see PatchGrowthEngine.isEligible's water-stays-gated rule).
    //
    // MUST stay declared above CONFIGS: CONFIGS's initializer calls loadPlaceholderConfig()
    // immediately, and Java runs static field initializers in textual/declaration order --
    // a real bug here (caught by a world-gen hang, not selfTest, since selfTest builds its
    // own independent biome list and never touches this class) had these declared BELOW
    // CONFIGS, so loadPlaceholderConfig() read them as null (not yet initialized),
    // silently giving every land biome a null regionFamilyWeights instead of the real
    // list -- NullPointerException the first time PatchGrowthEngine.rollRegionFamily
    // called regionFamilyWeights().isEmpty() on it, during real chunk generation (the
    // eager stronghold-ring structure scan, same trigger DESIGN.md Sec 11's earlier hangs
    // had). See loadStartupSanityCheckedConfig below for the runtime guard against this
    // exact class of mistake recurring silently.
    private static final List<RegionFamilyWeight> FLAT_GENTLE_FAMILIES = List.of(
        new RegionFamilyWeight(RegionFamily.CLUB_WEAK, 0.6f),
        new RegionFamilyWeight(RegionFamily.SPADE_WEAK, 0.4f)
    );
    // Excludes HEART (jungle_pillar's family) on purpose -- sand spires in desert-assigned
    // terrain was the original confirmed symptom this whole phase exists to fix.
    private static final List<RegionFamilyWeight> DESERT_FAMILIES = List.of(
        new RegionFamilyWeight(RegionFamily.SPADE, 0.5f),
        new RegionFamilyWeight(RegionFamily.DIAMOND, 0.5f)
    );
    private static final List<RegionFamilyWeight> MOUNTAINOUS_FAMILIES = List.of(
        new RegionFamilyWeight(RegionFamily.SPADE, 0.6f),
        new RegionFamilyWeight(RegionFamily.DIAMOND, 0.4f)
    );

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<TerrainClass> LAND_TERRAIN_CLASSES = Set.of(
        TerrainClass.FLAT_LAND, TerrainClass.HILLY, TerrainClass.MOUNTAINOUS
    );

    private static final List<BiomeConfig> CONFIGS = loadStartupSanityCheckedConfig();

    /** Largest size_max across every configured biome -- SurfacePatchGrid sizes its
     * resolution window off this so patches aren't routinely truncated by the window
     * edge instead of their real termination rule. */
    public static final int MAX_SIZE_MAX_BLOCKS = CONFIGS.stream().mapToInt(BiomeConfig::sizeMaxBlocks).max().orElse(0);

    private BiomeConfigRegistry() {
    }

    public static List<BiomeConfig> allConfigs() {
        return CONFIGS;
    }

    /** Logs the loaded config so a world-gen hang has something to look at besides
     * silence (user-requested: this codebase's only prior world-creation hangs, per
     * DESIGN.md Sec 11, were invisible until deliberately instrumented). Also fails
     * fast, loudly, at mod load -- not lazily inside chunk generation -- on the exact
     * class of mistake this file's own history just produced: a null
     * regionFamilyWeights (should always be a real, possibly-empty List, never null)
     * or a land-terrain-class biome silently declaring no shape intent at all. */
    private static List<BiomeConfig> loadStartupSanityCheckedConfig() {
        List<BiomeConfig> configs = loadPlaceholderConfig();
        LOGGER.info("[worldgen] BiomeConfigRegistry loaded {} biome configs: {}",
            configs.size(), configs.stream().map(c -> c.id().location().toString()).toList());
        for (BiomeConfig config : configs) {
            if (config.regionFamilyWeights() == null) {
                throw new IllegalStateException(
                    "[worldgen] BiomeConfig " + config.id().location() + " has a null regionFamilyWeights -- "
                        + "should be a real (possibly empty) List. This is the exact static-init-order mistake "
                        + "documented above FLAT_GENTLE_FAMILIES; check declaration order in this file."
                );
            }
            boolean declaresLand = config.terrainClasses().stream().anyMatch(LAND_TERRAIN_CLASSES::contains);
            if (declaresLand && config.regionFamilyWeights().isEmpty()) {
                LOGGER.warn(
                    "[worldgen] BiomeConfig {} declares a land TerrainClass but has empty regionFamilyWeights -- "
                        + "it will stay fully reactive to real Tectonic terrain shape everywhere (Phase 2 intent "
                        + "never applies to it). Intentional for a biome you want fully vanilla-Tectonic-shaped; "
                        + "otherwise this is a silent authoring gap.",
                    config.id().location()
                );
            }
        }
        return configs;
    }

    private static List<BiomeConfig> loadPlaceholderConfig() {
        return List.of(
            new BiomeConfig(
                PLAINS, Set.of(TerrainClass.FLAT_LAND, TerrainClass.COAST), 400, 900, 1.0f,
                List.of(FOREST, SAVANNA), List.of(), List.of(), FLAT_GENTLE_FAMILIES
            ),
            new BiomeConfig(
                DESERT, Set.of(TerrainClass.FLAT_LAND, TerrainClass.COAST), 900, 1900, 0.8f,
                List.of(SAVANNA), List.of(SNOWY_PLAINS), List.of(), DESERT_FAMILIES
            ),
            new BiomeConfig(
                SNOWY_PLAINS, Set.of(TerrainClass.FLAT_LAND), 300, 600, 0.8f,
                List.of(), List.of(DESERT, SAVANNA), List.of(), FLAT_GENTLE_FAMILIES
            ),
            new BiomeConfig(
                SAVANNA, Set.of(TerrainClass.FLAT_LAND, TerrainClass.COAST), 350, 700, 0.8f,
                List.of(DESERT, PLAINS), List.of(SNOWY_PLAINS), List.of(), FLAT_GENTLE_FAMILIES
            ),
            new BiomeConfig(
                FOREST, Set.of(TerrainClass.FLAT_LAND, TerrainClass.HILLY), 250, 550, 1.0f,
                List.of(PLAINS, WINDSWEPT_HILLS), List.of(), List.of(), FLAT_GENTLE_FAMILIES
            ),
            new BiomeConfig(
                OCEAN, Set.of(TerrainClass.OCEAN), 500, 1000, 1.0f,
                List.of(), List.of(), List.of(), List.of()
            ),
            new BiomeConfig(
                WINDSWEPT_HILLS, Set.of(TerrainClass.HILLY, TerrainClass.MOUNTAINOUS), 200, 450, 0.6f,
                List.of(FOREST), List.of(), List.of(), MOUNTAINOUS_FAMILIES
            ),
            new BiomeConfig(
                RIVER, Set.of(TerrainClass.RIVER), 200, 1000, 1.0f,
                List.of(), List.of(), List.of(), List.of()
            ),
            new BiomeConfig(
                DRIPSTONE_CAVES, Set.of(), 0, 0, 1.0f,
                List.of(), List.of(), List.of(WINDSWEPT_HILLS, DESERT), List.of()
            ),
            new BiomeConfig(
                LUSH_CAVES, Set.of(), 0, 0, 1.0f,
                List.of(), List.of(), List.of(FOREST, PLAINS), List.of()
            )
        );
    }

    private static ResourceKey<Biome> biomeKey(String namespace, String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }
}
