package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** A copy of the graph with one law loosened, for the Verify run. Shape and presence are not graph laws: not loosenable here. */
final class Loosening {

    /** User decision 2026-10-31: widen a size range by min -20%, max +25%. */
    private static final double MIN_WIDTH_FACTOR = 0.8;
    private static final double MAX_WIDTH_FACTOR = 1.25;
    /** Blend width when the graph has no edge to copy one from - the value every current edge uses. */
    private static final int DEFAULT_BLEND_WIDTH_BLOCKS = 10;

    private Loosening() {
    }

    static Optional<LoosenedGraph> of(Obstacle obstacle, BiomeGraphData catalog) {
        if (!(obstacle instanceof LawObstacle lawObstacle)) {
            return Optional.empty();
        }
        return Optional.of(switch (lawObstacle.law()) {
            case IllegalPairLaw pair -> legalize(pair, catalog);
            case SizeRangeLaw size -> widen(size.biome(), catalog);
            case RequiredBorderLaw border -> dropRequirement(border.biome(), catalog);
        });
    }

    private static LoosenedGraph legalize(IllegalPairLaw pair, BiomeGraphData catalog) {
        List<EdgeData> edges = new ArrayList<>(catalog.edges());
        edges.add(new EdgeData(pair.first().namespace(), pair.first().path(), pair.second().namespace(), pair.second().path(), mostCommonBlendWidth(catalog)));
        return new LoosenedGraph("make " + pair.first() + " and " + pair.second() + " legal neighbours", new BiomeGraphData(catalog.nodes(), edges, catalog.groups()));
    }

    /** New edges get the blend width most edges already use, so only legality changes. */
    private static int mostCommonBlendWidth(BiomeGraphData catalog) {
        Map<Integer, Long> countByWidth = catalog.edges().stream().collect(Collectors.groupingBy(EdgeData::blendWidthBlocks, Collectors.counting()));
        return countByWidth.entrySet().stream().max(Map.Entry.<Integer, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .orElse(DEFAULT_BLEND_WIDTH_BLOCKS);
    }

    private static LoosenedGraph widen(BiomeId biome, BiomeGraphData catalog) {
        NodeData node = nodeOf(biome, catalog);
        int min = (int) Math.floor(node.minWidthBlocks() * MIN_WIDTH_FACTOR);
        int max = (int) Math.ceil(node.maxWidthBlocks() * MAX_WIDTH_FACTOR);
        if (min < 1) {
            throw new IllegalArgumentException("Loosening: widening " + biome + " min width " + node.minWidthBlocks() + " blocks would give " + min + "; a width is at least 1");
        }
        BiomeGraphData widened = withNode(biome, catalog, old -> new NodeData(old.namespace(), old.path(), min, max, old.selfAdjacencyLimit(), old.requirement()));
        return new LoosenedGraph("widen " + biome + " size range " + node.minWidthBlocks() + ".." + node.maxWidthBlocks() + " -> " + min + ".." + max + " blocks", widened);
    }

    private static LoosenedGraph dropRequirement(BiomeId biome, BiomeGraphData catalog) {
        NodeData node = nodeOf(biome, catalog);
        BiomeGraphData dropped = withNode(biome, catalog, old -> new NodeData(old.namespace(), old.path(), old.minWidthBlocks(), old.maxWidthBlocks(), old.selfAdjacencyLimit(), ""));
        return new LoosenedGraph("drop " + biome + " required border (was: " + node.requirement() + ")", dropped);
    }

    private static NodeData nodeOf(BiomeId biome, BiomeGraphData catalog) {
        return catalog.nodes().stream().filter(node -> node.key().equals(biome.toString())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Loosening: " + biome + " is not in this graph"));
    }

    private static BiomeGraphData withNode(BiomeId biome, BiomeGraphData catalog, UnaryOperator<NodeData> change) {
        Function<NodeData, NodeData> changeOnlyThatBiome = node -> node.key().equals(biome.toString()) ? change.apply(node) : node;
        return new BiomeGraphData(catalog.nodes().stream().map(changeOnlyThatBiome).toList(), catalog.edges(), catalog.groups());
    }
}
