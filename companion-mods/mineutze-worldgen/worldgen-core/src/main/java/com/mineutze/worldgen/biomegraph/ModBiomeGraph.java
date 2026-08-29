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
            // Closes the LAST real gap in this graph, found via a real production crash (the
            // geometry-realization rasterizer of the time - since replaced by
            // SeamGrowthRasterizer - hit a genuine dead end on worldSeed=42): badlands and
            // ocean are legal to EACH OTHER but, before this rule, shared no THIRD common
            // neighbor at all (badlands' other neighbors are desert/savanna; ocean's other
            // neighbors are forest/plains - disjoint sets), so any cell whose neighbors
            // included both badlands and ocean plus two more biomes had NO legal placement.
            // Verified exhaustively (not just this one pair, every combination up to 6 - the
            // maximum possible, since this graph only has 6 biomes): with this rule added,
            // requireEveryLocalNeighborhoodSolvable(8) below actually proves zero unsolvable
            // combinations exist at ANY size, not just reduces how often one is hit. Savanna
            // was chosen over the alternatives that were also checked (ocean-desert only
            // closed 3 of 4 gaps; badlands-forest only closed 3 of 4 gaps, a DIFFERENT one
            // left over) because it's the only single addition that closes all of them, and
            // coastal savanna is a normal, common real-world pairing.
            AdjacencyRule.allow(OCEAN, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS)
        ),
        8
    );

    private ModBiomeGraph() {
    }
}
