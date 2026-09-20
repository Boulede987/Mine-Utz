package com.mineutze.worldgen.biomegraph;

import java.util.List;

/**
 * The shipped biome graph, loaded from {@code /biomegraph/biome-graph.json}.
 *
 * Lives in worldgen-core (not the mod module) despite the "Mod" name - {@link BiomeId}
 * deliberately not Minecraft's ResourceLocation, so no Minecraft/NeoForge dependency
 * here either, and both mod + graph editor need the same graph instance.
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

    /**
     * Real near-complete vanilla biome catalog, loaded from the graph editor's own saved
     * data ({@code src/main/resources/biomegraph/biome-graph.json}) - editing that file via
     * the graph editor tool ({@code com.mineutze.worldgen.tooling.graphedit.GraphEditorServer})
     * and rebuilding the mod IS how this catalog changes now, no separate hand-written Java
     * block to keep in sync. {@link BiomeGraphJson#toProductionBiomeGraph} applies the same
     * {@link BiomeGraph#forProduction} hard validation (requireMinLegalNeighbors etc.) this
     * catalog always went through as a hand-written block.
     */
    public static final BiomeGraph OVERWORLD = BiomeGraphJson.toProductionBiomeGraph(
        BiomeGraphJson.loadResource(ModBiomeGraph.class, "/biomegraph/biome-graph.json")
    );

    private ModBiomeGraph() {
    }
}
