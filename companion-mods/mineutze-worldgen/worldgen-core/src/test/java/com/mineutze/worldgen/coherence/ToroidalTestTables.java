package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.List;

/** Small hand-built rule tables shared by the toroidal tests. */
final class ToroidalTestTables {

    static final BiomeId A = new BiomeId("test", "a");
    static final BiomeId B = new BiomeId("test", "b");
    static final BiomeId C = new BiomeId("test", "c");
    static final BiomeId D = new BiomeId("test", "d");

    private ToroidalTestTables() {
    }

    /** Every pair of the given biomes legal - a complete graph, so any 3-colorable lattice tiling is legal. */
    static RawRuleTable fullyLegalTable(BiomeId... biomes) {
        List<NodeData> nodes = new ArrayList<>();
        List<EdgeData> edges = new ArrayList<>();
        for (int first = 0; first < biomes.length; first++) {
            nodes.add(new NodeData(biomes[first].namespace(), biomes[first].path(), 500, 800, Integer.MAX_VALUE, List.of()));
            for (int second = first + 1; second < biomes.length; second++) {
                edges.add(new EdgeData(biomes[first].namespace(), biomes[first].path(), biomes[second].namespace(), biomes[second].path(), 10));
            }
        }
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    /** Only A-B legal; C isolated. A hex lattice needs three mutually legal biomes (every cell plus two consecutive neighbors touch), so no legal tiling exists. */
    static RawRuleTable tableWithNoLegalTriangle() {
        BiomeGraphData data = new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(B.namespace(), B.path(), 500, 800, Integer.MAX_VALUE, List.of()),
                new NodeData(C.namespace(), C.path(), 500, 800, Integer.MAX_VALUE, List.of())
            ),
            List.of(new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10))
        );
        return RawRuleTable.from(data);
    }
}
