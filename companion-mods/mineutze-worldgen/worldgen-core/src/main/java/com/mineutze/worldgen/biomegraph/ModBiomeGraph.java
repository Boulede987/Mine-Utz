package com.mineutze.worldgen.biomegraph;

/**
 * The shipped biome graph, loaded from {@code /biomegraph/biome-graph.json}.
 *
 * Lives in worldgen-core (not the mod module) despite the "Mod" name - {@link BiomeId}
 * deliberately not Minecraft's ResourceLocation, so no Minecraft/NeoForge dependency
 * here either, and both mod + graph editor need the same graph instance.
 */
public final class ModBiomeGraph {

    /**
     * Real near-complete vanilla biome catalog, loaded from the graph editor's own saved
     * data ({@code src/main/resources/biomegraph/biome-graph.json}) - editing that file via
     * the graph editor tool ({@code com.mineutze.worldgen.tooling.graphedit.GraphEditorServer})
     * and rebuilding the mod IS how this catalog changes now. {@link
     * BiomeGraphJson#toProductionBiomeGraph} applies the same {@link BiomeGraph#forProduction}
     * hard validation (requireMinLegalNeighbors etc.) any real catalog must pass.
     */
    public static final BiomeGraph OVERWORLD = BiomeGraphJson.toProductionBiomeGraph(
        BiomeGraphJson.loadResource(ModBiomeGraph.class, "/biomegraph/biome-graph.json")
    );

    private ModBiomeGraph() {
    }
}
