package com.mineutze.worldgen.biomegraph;

import java.util.List;

/**
 * First real BiomeGraph, matching DESIGN.md's worked example. Shape hints
 * are inert until the Density Router exists — placeholder values, not yet
 * consumed by anything. Everything here (widths, blend width) is a
 * placeholder to tune from logged solve output, same as the grid constants
 * in {@link com.mineutze.worldgen.regionsolve.MacroGridConfig}.
 *
 * Lives in worldgen-core (not the mod module) despite the "Mod" name —
 * {@link BiomeId} is deliberately not Minecraft's ResourceLocation, so this
 * class has no Minecraft/NeoForge dependency either, and both the mod and
 * the standalone export CLI ({@code com.mineutze.worldgen.export}) need the
 * same graph instance.
 */
public final class ModBiomeGraph {

    private static final int DEFAULT_BLEND_WIDTH_BLOCKS = 10;

    // Shape identity per the cohesion-profile design: how tightly a
    // growing patch must hold together (see GrowthCohesionProfile's own doc). Every
    // biome uses COMPACT for now, including plains — no biome gets a permanently
    // looser shape identity just because it's common, same reasoning as removing its
    // old universal-filler status below.
    private static final GrowthCohesionProfile COMPACT = new GrowthCohesionProfile(2, 0, 0);
    // TODO: directionalRunMajor/Minor's hard per-cell gate
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

    public static final BiomeGraph DEFAULT = BiomeGraph.forProduction(
        List.of(
            new BiomeNode(DESERT, new BiomeSizeRange(700, 1000), new BiomeShapeHints(0.1, 0.5, 0.9), new BiomeFeatureToggles(false, false), COMPACT),
            new BiomeNode(SAVANNA, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.4, 0.5, 0.6), new BiomeFeatureToggles(true, true), COMPACT),
            new BiomeNode(BADLANDS, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.8, 0.6, 0.2), new BiomeFeatureToggles(false, false), COMPACT),
            new BiomeNode(OCEAN, new BiomeSizeRange(600, 1000), new BiomeShapeHints(0.3, 0.9, 0.5), new BiomeFeatureToggles(true, true), ELONGATED),
            new BiomeNode(FOREST, new BiomeSizeRange(500, 800), new BiomeShapeHints(0.5, 0.4, 0.5), new BiomeFeatureToggles(true, true), COMPACT),
            new BiomeNode(PLAINS, new BiomeSizeRange(300, 600), new BiomeShapeHints(0.2, 0.3, 0.8), new BiomeFeatureToggles(true, true), COMPACT)
        ),
        List.of(
            AdjacencyRule.allow(DESERT, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(DESERT, BADLANDS, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(OCEAN, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, DESERT, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            // PLAINS-BADLANDS deliberately NOT allowed - plains is no longer a universal
            // filler, it's a normal biome with real restrictions like everything else.
            AdjacencyRule.allow(PLAINS, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(PLAINS, FOREST, DEFAULT_BLEND_WIDTH_BLOCKS),
            // BiomeGraph enforces a minimum of 3 legal neighbors per node (its own doc:
            // fewer starves a biome under TriangleIdentitySizeAssignment's fairness-quota
            // ordering, confirmed by badlands originally sitting at degree 1 here). These
            // 3 rules bring badlands/savanna/ocean/forest up to the floor without touching
            // the deliberate plains-badlands exclusion above.
            AdjacencyRule.allow(BADLANDS, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(BADLANDS, OCEAN, DEFAULT_BLEND_WIDTH_BLOCKS),
            AdjacencyRule.allow(FOREST, SAVANNA, DEFAULT_BLEND_WIDTH_BLOCKS),
            // Closes the LAST real gap in this graph, found via a real production crash
            // (the geometry-realization rasterizer of the time - LegalityConstrainedRasterizer,
            // since replaced by SeamGrowthRasterizer - hit a genuine dead end on
            // worldSeed=42): badlands
            // and ocean are legal to EACH OTHER but, before this rule, shared no THIRD common
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
