package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.RequiredNeighbor;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return fullyLegalTableWithWidths(500, 800, biomes);
    }

    /** As {@link #fullyLegalTable}, with every biome given the same size range (min/max are the width of an equal-area regular hexagon, like the real catalog's). */
    static RawRuleTable fullyLegalTableWithWidths(int minWidthBlocks, int maxWidthBlocks, BiomeId... biomes) {
        return RawRuleTable.from(fullyLegalCatalog(minWidthBlocks, maxWidthBlocks, biomes));
    }

    /** The catalog data behind {@link #fullyLegalTableWithWidths}, for tests that go through the public plan API. */
    static BiomeGraphData fullyLegalCatalog(int minWidthBlocks, int maxWidthBlocks, BiomeId... biomes) {
        List<NodeData> nodes = new ArrayList<>();
        List<EdgeData> edges = new ArrayList<>();
        for (int first = 0; first < biomes.length; first++) {
            nodes.add(new NodeData(biomes[first].namespace(), biomes[first].path(), minWidthBlocks, maxWidthBlocks, Integer.MAX_VALUE, List.of()));
            for (int second = first + 1; second < biomes.length; second++) {
                edges.add(new EdgeData(biomes[first].namespace(), biomes[first].path(), biomes[second].namespace(), biomes[second].path(), 10));
            }
        }
        return new BiomeGraphData(nodes, edges);
    }

    /** As {@link #fullyLegalTableWithWidths}, but {@code requirer} must share at least {@code minEdges} cell-edges with {@code required}. */
    static RawRuleTable fullyLegalTableRequiring(int minWidthBlocks, int maxWidthBlocks, BiomeId requirer, BiomeId required, int minEdges, BiomeId... biomes) {
        return RawRuleTable.from(withRequirement(fullyLegalCatalog(minWidthBlocks, maxWidthBlocks, biomes), requirer, required, minEdges));
    }

    /** {@code data} with a "shares at least {@code minEdges} edges with {@code required}" requirement added to {@code requirer}. */
    static BiomeGraphData withRequirement(BiomeGraphData data, BiomeId requirer, BiomeId required, int minEdges) {
        List<NodeData> nodes = data.nodes().stream()
            .map(node -> node.namespace().equals(requirer.namespace()) && node.path().equals(requirer.path())
                ? new NodeData(node.namespace(), node.path(), node.minWidthBlocks(), node.maxWidthBlocks(), node.selfAdjacencyLimit(),
                    List.of(new RequiredNeighbor(required.namespace(), required.path(), minEdges)))
                : node)
            .toList();
        return new BiomeGraphData(nodes, data.edges());
    }

    /** Two mutually legal biomes with DIFFERENT size ranges, for tests where one biome's size must be pinned tightly and the other left loose. */
    static RawRuleTable legalPairWithSizes(BiomeId first, int firstMinWidth, int firstMaxWidth, BiomeId second, int secondMinWidth, int secondMaxWidth) {
        List<NodeData> nodes = List.of(
            new NodeData(first.namespace(), first.path(), firstMinWidth, firstMaxWidth, Integer.MAX_VALUE, List.of()),
            new NodeData(second.namespace(), second.path(), secondMinWidth, secondMaxWidth, Integer.MAX_VALUE, List.of())
        );
        List<EdgeData> edges = List.of(new EdgeData(first.namespace(), first.path(), second.namespace(), second.path(), 10));
        return RawRuleTable.from(new BiomeGraphData(nodes, edges));
    }

    /** A proper 3-coloring of the hex torus: color = (q - r) mod 3 differs across every neighbor direction. Only well-defined when both sides are multiples of 3. */
    static Map<ToroidalPos, BiomeId> threeColoring(ToroidalGrid grid, BiomeId first, BiomeId second, BiomeId third) {
        List<BiomeId> colors = List.of(first, second, third);
        Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            assignment.put(pos, colors.get(Math.floorMod(pos.q() - pos.r(), 3)));
        }
        return assignment;
    }

    static Map<ToroidalPos, BiomeId> everyCellHolds(ToroidalGrid grid, BiomeId biome) {
        Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            assignment.put(pos, biome);
        }
        return assignment;
    }

    /** Only A-B legal; C isolated. A hex lattice needs three mutually legal biomes (every cell plus two consecutive neighbors touch), so no legal tiling exists. */
    static RawRuleTable tableWithNoLegalTriangle() {
        return tableWithNoLegalTriangle(500, 800);
    }

    static RawRuleTable tableWithNoLegalTriangle(int minWidthBlocks, int maxWidthBlocks) {
        return RawRuleTable.from(noLegalTriangleCatalog(minWidthBlocks, maxWidthBlocks));
    }

    static BiomeGraphData noLegalTriangleCatalog(int minWidthBlocks, int maxWidthBlocks) {
        return new BiomeGraphData(
            List.of(
                new NodeData(A.namespace(), A.path(), minWidthBlocks, maxWidthBlocks, Integer.MAX_VALUE, List.of()),
                new NodeData(B.namespace(), B.path(), minWidthBlocks, maxWidthBlocks, Integer.MAX_VALUE, List.of()),
                new NodeData(C.namespace(), C.path(), minWidthBlocks, maxWidthBlocks, Integer.MAX_VALUE, List.of())
            ),
            List.of(new EdgeData(A.namespace(), A.path(), B.namespace(), B.path(), 10))
        );
    }
}
