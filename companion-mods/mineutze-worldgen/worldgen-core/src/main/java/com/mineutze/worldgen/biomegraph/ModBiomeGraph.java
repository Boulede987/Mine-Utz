package com.mineutze.worldgen.biomegraph;

import java.util.List;

/**
 * First real BiomeGraph, matching DESIGN.md's worked example. Everything here
 * (widths, blend width) is a placeholder to tune from logged solve output,
 * same as the grid constants in {@link com.mineutze.worldgen.regionsolve.MacroGridConfig}.
 *
 * Lives in worldgen-core (not the mod module) despite the "Mod" name —
 * {@link BiomeId} is deliberately not Minecraft's ResourceLocation, so this
 * class has no Minecraft/NeoForge dependency either, and both the mod and
 * the standalone export CLI ({@code com.mineutze.worldgen.export}) need the
 * same graph instance.
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
            AdjacencyRule.allow(OCEAN, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS)
        ),
        8
    );

    private ModBiomeGraph() {
    }
}
