package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * One-off export: a real {@link BiomeGraph} -&gt; {@link GraphEditorFiles#DATA_FILE}.
 * Not part of the editor's own request/response loop - a dev-run utility ("reset the
 * editor's file back to whatever a given BiomeGraph currently is" - useful for a one-time
 * migration, or recovering from a bad edit by reseeding from a known-good in-memory
 * graph). {@link BiomeGraph} exposes no direct rule-list accessor, so rules are recovered
 * by checking every real node pair's own {@link BiomeGraph#isAllowed} - {@code O(n^2)}
 * over 48 real biomes (~1128 pairs), a one-time cost, not a live-loop concern.
 */
final class GraphDataSeeder {

    private GraphDataSeeder() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = fromProductionGraph(ModBiomeGraph.OVERWORLD);
        BiomeGraphJson.save(GraphEditorFiles.DATA_FILE, data);
        System.out.println("GraphDataSeeder: wrote " + data.nodes().size() + " nodes, " + data.edges().size()
            + " edges to " + GraphEditorFiles.DATA_FILE.toAbsolutePath());
    }

    static BiomeGraphData fromProductionGraph(BiomeGraph graph) {
        List<BiomeId> allBiomes = graph.nodes().stream().map(BiomeNode::id).sorted().toList();

        List<NodeData> nodes = new ArrayList<>();
        for (BiomeId id : allBiomes) {
            BiomeNode node = graph.node(id);
            nodes.add(new NodeData(
                id.namespace(), id.path(), node.sizeRange().minWidthBlocks(), node.sizeRange().maxWidthBlocks(), node.selfAdjacencyLimit()
            ));
        }

        List<EdgeData> edges = new ArrayList<>();
        for (int i = 0; i < allBiomes.size(); i++) {
            for (int j = i + 1; j < allBiomes.size(); j++) {
                BiomeId a = allBiomes.get(i);
                BiomeId b = allBiomes.get(j);
                if (graph.isAllowed(a, b)) {
                    edges.add(new EdgeData(a.namespace(), a.path(), b.namespace(), b.path(), graph.rule(a, b).blendWidthBlocks()));
                }
            }
        }
        return new BiomeGraphData(nodes, edges);
    }
}
