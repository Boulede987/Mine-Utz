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
public record BiomeGraphData(List<NodeData> nodes, List<EdgeData> edges) {

    /**
     * Mirrors {@link BiomeNode} + its {@link BiomeId}, flattened to scalars for JSON, plus
     * {@code requiredNeighbors} - not part of {@link BiomeNode}/the live algorithm at all
     * (2026-09-16: no such constraint exists anywhere in the real solver, confirmed by
     * grep before adding this - it was only ever in the original design statement). Added
     * here, schema-only, for {@code com.mineutze.worldgen.coherence}'s own existence proof,
     * which needs to check it even though nothing today authors it. Defaults to empty -
     * every existing saved graph still loads unchanged.
     */
    public record NodeData(
        String namespace, String path, int minWidthBlocks, int maxWidthBlocks, int selfAdjacencyLimit,
        List<RequiredNeighbor> requiredNeighbors
    ) {
        public String key() {
            return namespace + ":" + path;
        }
    }

    /** One "this biome must have at least {@code minCount} real neighbors that are exactly this other biome" rule. */
    public record RequiredNeighbor(String namespace, String path, int minCount) {
        public String key() {
            return namespace + ":" + path;
        }
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
