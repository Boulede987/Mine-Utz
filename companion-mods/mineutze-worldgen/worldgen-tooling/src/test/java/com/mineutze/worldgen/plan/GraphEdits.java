package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.stream.Stream;

/** Graph edits a user makes in the editor, for tests of what a saved ranking keeps afterwards. */
final class GraphEdits {

    private static final int BLEND_WIDTH_BLOCKS = 10;

    private GraphEdits() {
    }

    static BiomeGraphData withLegalPair(BiomeGraphData catalog, BiomeId first, BiomeId second) {
        EdgeData edge = new EdgeData(first.namespace(), first.path(), second.namespace(), second.path(), BLEND_WIDTH_BLOCKS);
        return new BiomeGraphData(catalog.nodes(), Stream.concat(catalog.edges().stream(), Stream.of(edge)).toList(), catalog.groups());
    }
}
