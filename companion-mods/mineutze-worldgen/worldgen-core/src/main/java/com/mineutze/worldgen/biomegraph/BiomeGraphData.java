package com.mineutze.worldgen.biomegraph;

import java.util.List;

/**
 * A flat, JSON-friendly mirror of {@link BiomeGraph}'s real shape (node + rule) - the
 * editable model both {@link ModBiomeGraph#OVERWORLD} (production loader, see {@link
 * BiomeGraphJson#toBiomeGraph}) and the graph editor tool ({@code
 * com.mineutze.worldgen.tooling.graphedit}, add/remove one entry at a time) share.
 *
 * <p>Real user decision (2026-09-15): production now loads its Overworld catalog FROM
 * this data (via {@link BiomeGraphJson}, a classpath resource - see {@code ModBiomeGraph}'s
 * own doc), no longer a hand-written Java constant list. Moved here from the tooling
 * package specifically so {@code ModBiomeGraph} (core production code) depends on this
 * (core {@code biomegraph} package), not the other way around - a dev tool depending on
 * production code is normal; production depending on a package named "tooling" is not.
 */
public record BiomeGraphData(List<NodeData> nodes, List<EdgeData> edges, List<GroupData> groups) {

    /** A graph without groups. */
    public BiomeGraphData(List<NodeData> nodes, List<EdgeData> edges) {
        this(nodes, edges, List.of());
    }

    /**
     * Mirrors {@link BiomeNode} + its {@link BiomeId}, flattened to scalars for JSON, plus
     * {@code requirement} - not part of {@link BiomeNode}: the borders every cluster of this biome
     * must share with other biomes (a beach with the ocean), as {@link BorderRequirement} text over
     * the graph's groups, enforced by the world generator. Blank means none.
     */
    public record NodeData(
        String namespace, String path, int minWidthBlocks, int maxWidthBlocks, int selfAdjacencyLimit,
        String requirement
    ) {
        public String key() {
            return namespace + ":" + path;
        }
    }

    /** A named set of biomes (the oceans, the forests) that a {@link BorderRequirement} can name; {@code members} are biome keys, {@code namespace:path}. */
    public record GroupData(String name, List<String> members) {
    }

    /** Mirrors {@link AdjacencyRule}, flattened to scalars for JSON. */
    public record EdgeData(String aNamespace, String aPath, String bNamespace, String bPath, int blendWidthBlocks) {
        public String aKey() {
            return aNamespace + ":" + aPath;
        }

        public String bKey() {
            return bNamespace + ":" + bPath;
        }
    }
}
