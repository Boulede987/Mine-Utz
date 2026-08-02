package com.mineutze.worldgen.biomegraph;

import java.util.List;

/**
 * First real BiomeGraph, matching DESIGN.md's worked example. Shape hints
 * are inert until the Density Router (doc/ARCHITECTURE.md layer 3) exists —
 * placeholder values, not yet consumed by anything. Everything here
 * (widths, blend width) is a placeholder to tune from logged solve output,
 * same as the grid constants in doc/ARCHITECTURE.md.
 *
 * Lives in worldgen-core (not the mod module) despite the "Mod" name —
 * {@link BiomeId} is deliberately not Minecraft's ResourceLocation, so this
 * class has no Minecraft/NeoForge dependency either, and both the mod and
 * the standalone export CLI ({@code com.mineutze.worldgen.export}) need the
 * same graph instance.
 */
public final class ModBiomeGraph {

    private static final int DEFAULT_BLEND_WIDTH_BLOCKS = 10;

    // Shape identity per doc/KNOWN_ISSUES.md's cohesion-profile design: how tightly a
    // growing patch must hold together (see GrowthCohesionProfile's own doc).
    // Every biome, including filler, uses COMPACT for now — GrowthCohesionProfile
    // only governs GrowthEngine's actively-grown patches, not GapFiller's leftover-cell
    // majority-vote sweep (doc/KNOWN_ISSUES.md's open item on that), so per-biome
    // differentiation (e.g. an UNRESTRICTED filler) isn't worth tuning yet — it can't
    // be the source of any 1-wide shapes still visible until GapFiller enforces
    // cohesion too.
    private static final GrowthCohesionProfile COMPACT = new GrowthCohesionProfile(2, 0, 0);
    // TODO(doc/KNOWN_ISSUES.md): directionalRunMajor/Minor's hard per-cell gate
    // deadlocks whenever minor >= 2 — the perpendicular "thickness" it demands can
    // only exist once multiple cells have already joined together, but growth claims
    // one cell at a time, so the first cell extending a new row/column can never have
    // it pre-satisfied. Confirmed via the export CLI: every ocean demand dropped in
    // every region with (major=4, minor=2). Falling back to COMPACT until the
    // directional check is redesigned (likely: a soft frontier-ordering preference
    // instead of a hard gate, layered on top of the still-hard, still-working
    // minSameBiomeNeighbors check).
    private static final GrowthCohesionProfile ELONGATED = COMPACT;

    private static final BiomeId DESERT = new BiomeId("minecraft", "desert");
    private static final BiomeId SAVANNA = new BiomeId("minecraft", "savanna");
    private static final BiomeId BADLANDS = new BiomeId("minecraft", "badlands");
    private static final BiomeId OCEAN = new BiomeId("minecraft", "ocean");
    private static final BiomeId FOREST = new BiomeId("minecraft", "forest");
    private static final BiomeId PLAINS = new BiomeId("minecraft", "plains");

    public static final BiomeGraph DEFAULT = new BiomeGraph(
        List.of(
            new BiomeNode(DESERT, new BiomeSizeRange(700, 1000), new BiomeShapeHints(0.1, 0.5, 0.9), new BiomeFeatureToggles(false, false), false, COMPACT),
            new BiomeNode(SAVANNA, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.4, 0.5, 0.6), new BiomeFeatureToggles(true, true), false, COMPACT),
            new BiomeNode(BADLANDS, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.8, 0.6, 0.2), new BiomeFeatureToggles(false, false), false, COMPACT),
            new BiomeNode(OCEAN, new BiomeSizeRange(600, 1000), new BiomeShapeHints(0.3, 0.9, 0.5), new BiomeFeatureToggles(true, true), false, ELONGATED),
            new BiomeNode(FOREST, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.5, 0.4, 0.5), new BiomeFeatureToggles(true, true), false, COMPACT),
            new BiomeNode(PLAINS, new BiomeSizeRange(300, 600), new BiomeShapeHints(0.2, 0.3, 0.8), new BiomeFeatureToggles(true, true), true, COMPACT)
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
