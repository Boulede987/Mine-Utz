package com.mineutze.worldgen.biomegraph;

import java.util.List;

/**
 * First real BiomeGraph, matching DESIGN.md's worked example. Widths/blend width here
 * are placeholders to tune from logged solve output, same as grid constants in {@link
 * com.mineutze.worldgen.regionsolve.MacroGridConfig}.
 *
 * Lives in worldgen-core (not the mod module) despite the "Mod" name - {@link BiomeId}
 * deliberately not Minecraft's ResourceLocation, so no Minecraft/NeoForge dependency
 * here either, and both mod + standalone export CLI ({@code com.mineutze.worldgen.export})
 * need the same graph instance.
 */
public final class ModBiomeGraph {

    private static final int DEFAULT_BLEND_WIDTH_BLOCKS = 10;

    private static final BiomeId DESERT = new BiomeId("minecraft", "desert");
    private static final BiomeId SAVANNA = new BiomeId("minecraft", "savanna");
    private static final BiomeId BADLANDS = new BiomeId("minecraft", "badlands");
    private static final BiomeId OCEAN = new BiomeId("minecraft", "ocean");
    private static final BiomeId FOREST = new BiomeId("minecraft", "forest");
    private static final BiomeId PLAINS = new BiomeId("minecraft", "plains");

    public static final BiomeGraph DEFAULT = BiomeGraph.forProduction(
        List.of(
            new BiomeNode(DESERT, new BiomeSizeRange(700, 1000)),
            new BiomeNode(SAVANNA, new BiomeSizeRange(500, 800)),
            new BiomeNode(BADLANDS, new BiomeSizeRange(500, 800)),
            new BiomeNode(OCEAN, new BiomeSizeRange(600, 1000)),
            new BiomeNode(FOREST, new BiomeSizeRange(500, 800)),
            new BiomeNode(PLAINS, new BiomeSizeRange(300, 600))
        ),
        List.of(
            AdjacencyRule.allow(DESERT, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(DESERT, BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(OCEAN, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, DESERT, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(BADLANDS, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(BADLANDS, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(FOREST, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            // see DECISIONS.md 2026-08-23
            AdjacencyRule.allow(OCEAN, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS)
        )
    );

    // --- OVERWORLD: real near-complete vanilla biome set (48 biomes, 260 hand-designed
    // symmetric adjacency rules), generated from biome_adjency_planner.xlsx's "Overworld
    // Adjacency" sheet - supersedes scratch_bfs.txt/scratch_bfs2.txt's earlier smaller
    // 12-biome bridge-hop-reachability pass. Workbook also has stub rows (all-denied,
    // degree 0) for modded biomes (Biomes O' Plenty, Biome's We've Gone, Underground
    // Worlds) and a few vanilla ones (river, frozen_river, cave biomes) - not real rules
    // yet, not included here. DESERT/SAVANNA/BADLANDS/OCEAN/FOREST/PLAINS above reused
    // as-is (same BiomeId values either way).

    private static final BiomeId BAMBOO_JUNGLE = new BiomeId("minecraft", "bamboo_jungle");
    private static final BiomeId BEACH = new BiomeId("minecraft", "beach");
    private static final BiomeId BIRCH_FOREST = new BiomeId("minecraft", "birch_forest");
    private static final BiomeId CHERRY_GROVE = new BiomeId("minecraft", "cherry_grove");
    private static final BiomeId COLD_OCEAN = new BiomeId("minecraft", "cold_ocean");
    private static final BiomeId DARK_FOREST = new BiomeId("minecraft", "dark_forest");
    private static final BiomeId DEEP_COLD_OCEAN = new BiomeId("minecraft", "deep_cold_ocean");
    private static final BiomeId DEEP_FROZEN_OCEAN = new BiomeId("minecraft", "deep_frozen_ocean");
    private static final BiomeId DEEP_LUKEWARM_OCEAN = new BiomeId("minecraft", "deep_lukewarm_ocean");
    private static final BiomeId DEEP_OCEAN = new BiomeId("minecraft", "deep_ocean");
    private static final BiomeId ERODED_BADLANDS = new BiomeId("minecraft", "eroded_badlands");
    private static final BiomeId FLOWER_FOREST = new BiomeId("minecraft", "flower_forest");
    private static final BiomeId FROZEN_OCEAN = new BiomeId("minecraft", "frozen_ocean");
    private static final BiomeId FROZEN_PEAKS = new BiomeId("minecraft", "frozen_peaks");
    private static final BiomeId GROVE = new BiomeId("minecraft", "grove");
    private static final BiomeId ICE_SPIKES = new BiomeId("minecraft", "ice_spikes");
    private static final BiomeId JAGGED_PEAKS = new BiomeId("minecraft", "jagged_peaks");
    private static final BiomeId JUNGLE = new BiomeId("minecraft", "jungle");
    private static final BiomeId LUKEWARM_OCEAN = new BiomeId("minecraft", "lukewarm_ocean");
    private static final BiomeId MANGROVE_SWAMP = new BiomeId("minecraft", "mangrove_swamp");
    private static final BiomeId MEADOW = new BiomeId("minecraft", "meadow");
    private static final BiomeId MUSHROOM_FIELDS = new BiomeId("minecraft", "mushroom_fields");
    private static final BiomeId OLD_GROWTH_BIRCH_FOREST = new BiomeId("minecraft", "old_growth_birch_forest");
    private static final BiomeId OLD_GROWTH_PINE_TAIGA = new BiomeId("minecraft", "old_growth_pine_taiga");
    private static final BiomeId OLD_GROWTH_SPRUCE_TAIGA = new BiomeId("minecraft", "old_growth_spruce_taiga");
    private static final BiomeId SAVANNA_PLATEAU = new BiomeId("minecraft", "savanna_plateau");
    private static final BiomeId SNOWY_BEACH = new BiomeId("minecraft", "snowy_beach");
    private static final BiomeId SNOWY_PLAINS = new BiomeId("minecraft", "snowy_plains");
    private static final BiomeId SNOWY_SLOPES = new BiomeId("minecraft", "snowy_slopes");
    private static final BiomeId SNOWY_TAIGA = new BiomeId("minecraft", "snowy_taiga");
    private static final BiomeId SPARSE_JUNGLE = new BiomeId("minecraft", "sparse_jungle");
    private static final BiomeId STONY_PEAKS = new BiomeId("minecraft", "stony_peaks");
    private static final BiomeId STONY_SHORE = new BiomeId("minecraft", "stony_shore");
    private static final BiomeId SUNFLOWER_PLAINS = new BiomeId("minecraft", "sunflower_plains");
    private static final BiomeId SWAMP = new BiomeId("minecraft", "swamp");
    private static final BiomeId TAIGA = new BiomeId("minecraft", "taiga");
    private static final BiomeId WARM_OCEAN = new BiomeId("minecraft", "warm_ocean");
    private static final BiomeId WINDSWEPT_FOREST = new BiomeId("minecraft", "windswept_forest");
    private static final BiomeId WINDSWEPT_GRAVELLY_HILLS = new BiomeId("minecraft", "windswept_gravelly_hills");
    private static final BiomeId WINDSWEPT_HILLS = new BiomeId("minecraft", "windswept_hills");
    private static final BiomeId WINDSWEPT_SAVANNA = new BiomeId("minecraft", "windswept_savanna");
    private static final BiomeId WOODED_BADLANDS = new BiomeId("minecraft", "wooded_badlands");

    private static final List<BiomeNode> OVERWORLD_NODES = List.of(
        new BiomeNode(BADLANDS, new BiomeSizeRange(500, 800)),
        new BiomeNode(BAMBOO_JUNGLE, new BiomeSizeRange(500, 800)),
        new BiomeNode(BEACH, new BiomeSizeRange(300, 600)),
        new BiomeNode(BIRCH_FOREST, new BiomeSizeRange(500, 800)),
        new BiomeNode(CHERRY_GROVE, new BiomeSizeRange(500, 800)),
        new BiomeNode(COLD_OCEAN, new BiomeSizeRange(700, 1000)),
        new BiomeNode(DARK_FOREST, new BiomeSizeRange(500, 800)),
        // selfAdjacencyLimit=1 on these 5 (deep-ocean family + desert) - each spans up to 2
        // hexes as one felt region. Each deep-ocean variant self-adjacent to ITSELF only,
        // not OTHER deep-ocean variants. See DECISIONS.md 2026-09-11.
        new BiomeNode(DEEP_COLD_OCEAN, new BiomeSizeRange(900, 1500), 1),
        new BiomeNode(DEEP_FROZEN_OCEAN, new BiomeSizeRange(900, 1500), 1),
        new BiomeNode(DEEP_LUKEWARM_OCEAN, new BiomeSizeRange(900, 1500), 1),
        new BiomeNode(DEEP_OCEAN, new BiomeSizeRange(900, 1500), 1),
        new BiomeNode(DESERT, new BiomeSizeRange(900, 1500), 1),
        new BiomeNode(ERODED_BADLANDS, new BiomeSizeRange(500, 800)),
        new BiomeNode(FLOWER_FOREST, new BiomeSizeRange(500, 800)),
        new BiomeNode(FOREST, new BiomeSizeRange(500, 800)),
        new BiomeNode(FROZEN_OCEAN, new BiomeSizeRange(700, 1000)),
        new BiomeNode(FROZEN_PEAKS, new BiomeSizeRange(300, 600)),
        new BiomeNode(GROVE, new BiomeSizeRange(500, 800)),
        new BiomeNode(ICE_SPIKES, new BiomeSizeRange(300, 600)),
        new BiomeNode(JAGGED_PEAKS, new BiomeSizeRange(300, 600)),
        new BiomeNode(JUNGLE, new BiomeSizeRange(500, 800)),
        new BiomeNode(LUKEWARM_OCEAN, new BiomeSizeRange(700, 1000)),
        new BiomeNode(MANGROVE_SWAMP, new BiomeSizeRange(500, 800)),
        new BiomeNode(MEADOW, new BiomeSizeRange(300, 600)),
        new BiomeNode(MUSHROOM_FIELDS, new BiomeSizeRange(500, 800)),
        new BiomeNode(OCEAN, new BiomeSizeRange(700, 1000)),
        new BiomeNode(OLD_GROWTH_BIRCH_FOREST, new BiomeSizeRange(500, 800)),
        new BiomeNode(OLD_GROWTH_PINE_TAIGA, new BiomeSizeRange(500, 800)),
        new BiomeNode(OLD_GROWTH_SPRUCE_TAIGA, new BiomeSizeRange(500, 800)),
        new BiomeNode(PLAINS, new BiomeSizeRange(300, 600)),
        new BiomeNode(SAVANNA, new BiomeSizeRange(500, 800)),
        new BiomeNode(SAVANNA_PLATEAU, new BiomeSizeRange(500, 800)),
        new BiomeNode(SNOWY_BEACH, new BiomeSizeRange(300, 600)),
        new BiomeNode(SNOWY_PLAINS, new BiomeSizeRange(300, 600)),
        new BiomeNode(SNOWY_SLOPES, new BiomeSizeRange(300, 600)),
        new BiomeNode(SNOWY_TAIGA, new BiomeSizeRange(500, 800)),
        new BiomeNode(SPARSE_JUNGLE, new BiomeSizeRange(500, 800)),
        new BiomeNode(STONY_PEAKS, new BiomeSizeRange(300, 600)),
        new BiomeNode(STONY_SHORE, new BiomeSizeRange(300, 600)),
        new BiomeNode(SUNFLOWER_PLAINS, new BiomeSizeRange(300, 600)),
        new BiomeNode(SWAMP, new BiomeSizeRange(500, 800)),
        new BiomeNode(TAIGA, new BiomeSizeRange(500, 800)),
        new BiomeNode(WARM_OCEAN, new BiomeSizeRange(700, 1000)),
        new BiomeNode(WINDSWEPT_FOREST, new BiomeSizeRange(500, 800)),
        new BiomeNode(WINDSWEPT_GRAVELLY_HILLS, new BiomeSizeRange(300, 600)),
        new BiomeNode(WINDSWEPT_HILLS, new BiomeSizeRange(300, 600)),
        new BiomeNode(WINDSWEPT_SAVANNA, new BiomeSizeRange(500, 800)),
        new BiomeNode(WOODED_BADLANDS, new BiomeSizeRange(500, 800))
    );

    private static final List<AdjacencyRule> OVERWORLD_RULES = List.of(
        AdjacencyRule.allow(BADLANDS, DESERT, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BADLANDS, ERODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BADLANDS, LUKEWARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BADLANDS, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BADLANDS, SAVANNA_PLATEAU, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BADLANDS, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BADLANDS, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BADLANDS, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BAMBOO_JUNGLE, CHERRY_GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BAMBOO_JUNGLE, FLOWER_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BAMBOO_JUNGLE, LUKEWARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BAMBOO_JUNGLE, MANGROVE_SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BAMBOO_JUNGLE, SPARSE_JUNGLE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BAMBOO_JUNGLE, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BAMBOO_JUNGLE, SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BAMBOO_JUNGLE, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BEACH, CHERRY_GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BEACH, LUKEWARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BEACH, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BEACH, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BEACH, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, CHERRY_GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, DARK_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, FLOWER_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, MEADOW, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, OLD_GROWTH_BIRCH_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, OLD_GROWTH_SPRUCE_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(BIRCH_FOREST, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, FLOWER_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, JUNGLE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, LUKEWARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, MEADOW, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, OLD_GROWTH_BIRCH_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(CHERRY_GROVE, SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, DEEP_COLD_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, DEEP_FROZEN_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, DEEP_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, FROZEN_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, FROZEN_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, ICE_SPIKES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, MUSHROOM_FIELDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, SNOWY_BEACH, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, SNOWY_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, SNOWY_SLOPES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(COLD_OCEAN, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, MEADOW, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, OLD_GROWTH_BIRCH_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, OLD_GROWTH_SPRUCE_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DARK_FOREST, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, DEEP_FROZEN_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, DEEP_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, FROZEN_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, FROZEN_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, ICE_SPIKES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, JAGGED_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, MUSHROOM_FIELDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_COLD_OCEAN, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_FROZEN_OCEAN, DEEP_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_FROZEN_OCEAN, FROZEN_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_FROZEN_OCEAN, FROZEN_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_FROZEN_OCEAN, ICE_SPIKES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_LUKEWARM_OCEAN, DEEP_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_LUKEWARM_OCEAN, LUKEWARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_LUKEWARM_OCEAN, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_LUKEWARM_OCEAN, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_OCEAN, FROZEN_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_OCEAN, ICE_SPIKES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_OCEAN, LUKEWARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_OCEAN, MUSHROOM_FIELDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_OCEAN, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DEEP_OCEAN, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DESERT, ERODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DESERT, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DESERT, SAVANNA_PLATEAU, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(DESERT, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ERODED_BADLANDS, LUKEWARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ERODED_BADLANDS, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ERODED_BADLANDS, SAVANNA_PLATEAU, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ERODED_BADLANDS, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ERODED_BADLANDS, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ERODED_BADLANDS, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ERODED_BADLANDS, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FLOWER_FOREST, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FLOWER_FOREST, JUNGLE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FLOWER_FOREST, MEADOW, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FLOWER_FOREST, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FLOWER_FOREST, OLD_GROWTH_BIRCH_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FLOWER_FOREST, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FLOWER_FOREST, SPARSE_JUNGLE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FLOWER_FOREST, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, MEADOW, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, OLD_GROWTH_BIRCH_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, OLD_GROWTH_SPRUCE_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, SPARSE_JUNGLE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FOREST, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_OCEAN, FROZEN_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_OCEAN, GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_OCEAN, ICE_SPIKES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_OCEAN, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        // see DECISIONS.md 2026-08-30
        AdjacencyRule.allow(FROZEN_OCEAN, SNOWY_BEACH, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_OCEAN, SNOWY_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_OCEAN, SNOWY_SLOPES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_OCEAN, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_PEAKS, GROVE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_PEAKS, ICE_SPIKES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_PEAKS, JAGGED_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_PEAKS, SNOWY_SLOPES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_PEAKS, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_PEAKS, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(FROZEN_PEAKS, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(GROVE, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(GROVE, SNOWY_BEACH, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(GROVE, SNOWY_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(GROVE, SNOWY_SLOPES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(GROVE, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(GROVE, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ICE_SPIKES, JAGGED_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ICE_SPIKES, MUSHROOM_FIELDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ICE_SPIKES, SNOWY_BEACH, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ICE_SPIKES, SNOWY_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ICE_SPIKES, SNOWY_SLOPES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(ICE_SPIKES, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JAGGED_PEAKS, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JAGGED_PEAKS, SNOWY_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JAGGED_PEAKS, SNOWY_SLOPES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JAGGED_PEAKS, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JAGGED_PEAKS, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JAGGED_PEAKS, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JAGGED_PEAKS, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JAGGED_PEAKS, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JUNGLE, LUKEWARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JUNGLE, MANGROVE_SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JUNGLE, SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(JUNGLE, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(LUKEWARM_OCEAN, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(LUKEWARM_OCEAN, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(LUKEWARM_OCEAN, SAVANNA_PLATEAU, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(LUKEWARM_OCEAN, SPARSE_JUNGLE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(LUKEWARM_OCEAN, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(LUKEWARM_OCEAN, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MANGROVE_SWAMP, SPARSE_JUNGLE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MANGROVE_SWAMP, SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MEADOW, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MEADOW, OLD_GROWTH_BIRCH_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MEADOW, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MEADOW, SPARSE_JUNGLE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MEADOW, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MEADOW, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(MUSHROOM_FIELDS, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OCEAN, SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OCEAN, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OCEAN, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OCEAN, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_BIRCH_FOREST, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_BIRCH_FOREST, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_BIRCH_FOREST, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_BIRCH_FOREST, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_PINE_TAIGA, OLD_GROWTH_SPRUCE_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_PINE_TAIGA, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_PINE_TAIGA, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_PINE_TAIGA, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_PINE_TAIGA, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_PINE_TAIGA, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_PINE_TAIGA, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_PINE_TAIGA, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_SPRUCE_TAIGA, PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_SPRUCE_TAIGA, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_SPRUCE_TAIGA, SAVANNA_PLATEAU, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_SPRUCE_TAIGA, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_SPRUCE_TAIGA, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_SPRUCE_TAIGA, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_SPRUCE_TAIGA, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(OLD_GROWTH_SPRUCE_TAIGA, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, SNOWY_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(PLAINS, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, SAVANNA_PLATEAU, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA_PLATEAU, STONY_PEAKS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA_PLATEAU, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA_PLATEAU, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA_PLATEAU, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA_PLATEAU, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA_PLATEAU, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SAVANNA_PLATEAU, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SNOWY_BEACH, SNOWY_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SNOWY_BEACH, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SNOWY_PLAINS, SNOWY_SLOPES, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SNOWY_PLAINS, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SNOWY_SLOPES, SNOWY_TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SNOWY_SLOPES, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SNOWY_SLOPES, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SNOWY_TAIGA, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SPARSE_JUNGLE, SWAMP, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(SPARSE_JUNGLE, WARM_OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_PEAKS, STONY_SHORE, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_PEAKS, SUNFLOWER_PLAINS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_PEAKS, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_PEAKS, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_PEAKS, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_PEAKS, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_PEAKS, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_PEAKS, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_SHORE, TAIGA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_SHORE, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_SHORE, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_SHORE, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(STONY_SHORE, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(TAIGA, WINDSWEPT_FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(TAIGA, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(TAIGA, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(TAIGA, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(TAIGA, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WARM_OCEAN, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WARM_OCEAN, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_FOREST, WINDSWEPT_GRAVELLY_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_FOREST, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_FOREST, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_FOREST, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_GRAVELLY_HILLS, WINDSWEPT_HILLS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_GRAVELLY_HILLS, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_HILLS, WINDSWEPT_SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_HILLS, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
        AdjacencyRule.allow(WINDSWEPT_SAVANNA, WOODED_BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS)
    );

    /** Real production validation lives in {@code ModBiomeGraphTest}'s own generation-based stress tests, not a construction-time combinatorial bound - see {@link BiomeGraph#forProduction}'s own doc for why. */
    public static final BiomeGraph OVERWORLD = BiomeGraph.forProduction(OVERWORLD_NODES, OVERWORLD_RULES);

    private ModBiomeGraph() {
    }
}
