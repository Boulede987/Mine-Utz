package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeFeatureToggles;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeShapeHints;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

import java.util.List;

/**
 * First real BiomeGraph, matching DESIGN.md's worked example. Shape hints
 * are inert until the Density Router (doc/ARCHITECTURE.md layer 3) exists —
 * placeholder values, not yet consumed by anything. Everything here
 * (widths, blend width) is a placeholder to tune from logged solve output,
 * same as the grid constants in doc/ARCHITECTURE.md.
 */
public final class ModBiomeGraph {

    private static final int DEFAULT_BLEND_WIDTH_BLOCKS = 10;

    private static final BiomeId DESERT = new BiomeId("minecraft", "desert");
    private static final BiomeId SAVANNA = new BiomeId("minecraft", "savanna");
    private static final BiomeId BADLANDS = new BiomeId("minecraft", "badlands");
    private static final BiomeId OCEAN = new BiomeId("minecraft", "ocean");
    private static final BiomeId FOREST = new BiomeId("minecraft", "forest");
    private static final BiomeId PLAINS = new BiomeId("minecraft", "plains");

    public static final BiomeGraph DEFAULT = new BiomeGraph(
        List.of(
            new BiomeNode(DESERT, new BiomeSizeRange(700, 1000), new BiomeShapeHints(0.1, 0.5, 0.9), new BiomeFeatureToggles(false, false), false),
            new BiomeNode(SAVANNA, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.4, 0.5, 0.6), new BiomeFeatureToggles(true, true), false),
            new BiomeNode(BADLANDS, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.8, 0.6, 0.2), new BiomeFeatureToggles(false, false), false),
            new BiomeNode(OCEAN, new BiomeSizeRange(600, 1000), new BiomeShapeHints(0.3, 0.9, 0.5), new BiomeFeatureToggles(true, true), false),
            new BiomeNode(FOREST, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.5, 0.4, 0.5), new BiomeFeatureToggles(true, true), false),
            new BiomeNode(PLAINS, new BiomeSizeRange(300, 600), new BiomeShapeHints(0.2, 0.3, 0.8), new BiomeFeatureToggles(true, true), true)
        ),
        List.of(
            AdjacencyRule.allow(DESERT, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(DESERT, BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(OCEAN, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, DESERT, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS)
        )
    );

    private ModBiomeGraph() {
    }
}
