package com.mineutze.worldgen.biomegraph;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;

/**
 * {@link BiomeGraphData} &lt;-&gt; JSON, over {@link MinimalJson} - schema-specific
 * mapping, file/resource read/write, and the one conversion to a real {@link BiomeGraph}
 * (see {@link #toBiomeGraph}) - shared by {@link ModBiomeGraph#OVERWORLD} (production
 * loader, classpath resource) and the graph editor tool (dev-time file read/write), so
 * neither has its own copy of the node/rule conversion.
 */
public final class BiomeGraphJson {

    private BiomeGraphJson() {
    }

    // ---- file I/O (dev-time editing - the graph editor tool) ----

    public static BiomeGraphData load(Path file) {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("BiomeGraphJson: could not read " + file, e);
        }
        return fromJson(json);
    }

    public static void save(Path file, BiomeGraphData data) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("BiomeGraphJson: could not write " + file, e);
        }
    }

    // ---- classpath resource I/O (production - see ModBiomeGraph) ----

    /**
     * Reads a bundled classpath resource (works identically from loose class files during
     * dev/test AND from inside the packaged mod jar - Java resource loading doesn't care
     * which). {@code resourceClass} supplies the classloader; {@code resourcePath} is
     * absolute within the jar (leading {@code /}), e.g. {@code "/biomegraph/biome-graph.json"}.
     */
    public static BiomeGraphData loadResource(Class<?> resourceClass, String resourcePath) {
        try (InputStream stream = resourceClass.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException(
                    "BiomeGraphJson: classpath resource " + resourcePath + " not found - expected it bundled under "
                        + "src/main/resources (see ModBiomeGraph's own doc), missing from this build's classpath entirely"
                );
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return fromJson(json);
        } catch (IOException e) {
            throw new UncheckedIOException("BiomeGraphJson: could not read classpath resource " + resourcePath, e);
        }
    }

    // ---- the real BiomeGraph conversion ----

    /**
     * The bare-constructor conversion - used by the graph editor's own live validation
     * ({@code GraphValidation}), which deliberately does NOT want {@link
     * BiomeGraph#forProduction}'s hard {@code requireMinLegalNeighbors} reject (would
     * break incremental "add one adjacency at a time" editing - see that class's own
     * doc). For the real production graph, see {@link #toProductionBiomeGraph}.
     */
    public static BiomeGraph toBiomeGraph(BiomeGraphData data) {
        return new BiomeGraph(nodesOf(data), rulesOf(data));
    }

    /**
     * The real production conversion - {@link BiomeGraph#forProduction}, same hard
     * {@code requireMinLegalNeighbors} check the hand-written catalog always went
     * through. Used by {@link ModBiomeGraph#OVERWORLD} - see that field's own doc for why
     * a saved-but-under-connected biome now makes the MOD ITSELF fail to load.
     */
    public static BiomeGraph toProductionBiomeGraph(BiomeGraphData data) {
        return BiomeGraph.forProduction(nodesOf(data), rulesOf(data));
    }

    private static List<BiomeNode> nodesOf(BiomeGraphData data) {
        return data.nodes().stream()
            .map(node -> new BiomeNode(
                new BiomeId(node.namespace(), node.path()), new BiomeSizeRange(node.minWidthBlocks(), node.maxWidthBlocks()), node.selfAdjacencyLimit()
            ))
            .toList();
    }

    private static List<AdjacencyRule> rulesOf(BiomeGraphData data) {
        return data.edges().stream()
            .map(edge -> AdjacencyRule.allow(
                new BiomeId(edge.aNamespace(), edge.aPath()), new BiomeId(edge.bNamespace(), edge.bPath()), edge.blendWidthBlocks()
            ))
            .toList();
    }

    // ---- JSON schema mapping ----

    public static String toJson(BiomeGraphData data) {
        List<Object> nodesJson = data.nodes().stream().<Object>map(BiomeGraphJson::nodeToJson).toList();
        List<Object> edgesJson = data.edges().stream().<Object>map(BiomeGraphJson::edgeToJson).toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nodes", nodesJson);
        root.put("edges", edgesJson);
        return MinimalJson.writeObject(root);
    }

    @SuppressWarnings("unchecked")
    public static BiomeGraphData fromJson(String json) {
        Map<String, Object> root = (Map<String, Object>) MinimalJson.parse(json);
        List<Object> nodesJson = (List<Object>) root.getOrDefault("nodes", List.of());
        List<Object> edgesJson = (List<Object>) root.getOrDefault("edges", List.of());
        List<NodeData> nodes = new ArrayList<>();
        for (Object nodeJson : nodesJson) {
            nodes.add(nodeFromJson((Map<String, Object>) nodeJson));
        }
        List<EdgeData> edges = new ArrayList<>();
        for (Object edgeJson : edgesJson) {
            edges.add(edgeFromJson((Map<String, Object>) edgeJson));
        }
        return new BiomeGraphData(nodes, edges);
    }

    private static Map<String, Object> nodeToJson(NodeData node) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("namespace", node.namespace());
        json.put("path", node.path());
        json.put("minWidthBlocks", node.minWidthBlocks());
        json.put("maxWidthBlocks", node.maxWidthBlocks());
        json.put("selfAdjacencyLimit", node.selfAdjacencyLimit());
        return json;
    }

    public static NodeData nodeFromJson(Map<String, Object> json) {
        return new NodeData(
            stringField(json, "namespace"), stringField(json, "path"),
            intField(json, "minWidthBlocks"), intField(json, "maxWidthBlocks"), intField(json, "selfAdjacencyLimit")
        );
    }

    private static Map<String, Object> edgeToJson(EdgeData edge) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("aNamespace", edge.aNamespace());
        json.put("aPath", edge.aPath());
        json.put("bNamespace", edge.bNamespace());
        json.put("bPath", edge.bPath());
        json.put("blendWidthBlocks", edge.blendWidthBlocks());
        return json;
    }

    public static EdgeData edgeFromJson(Map<String, Object> json) {
        return new EdgeData(
            stringField(json, "aNamespace"), stringField(json, "aPath"),
            stringField(json, "bNamespace"), stringField(json, "bPath"),
            intField(json, "blendWidthBlocks")
        );
    }

    private static String stringField(Map<String, Object> json, String field) {
        Object value = json.get(field);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("BiomeGraphJson: expected string field \"" + field + "\", got " + value);
        }
        return stringValue;
    }

    private static int intField(Map<String, Object> json, String field) {
        Object value = json.get(field);
        if (!(value instanceof Double numberValue)) {
            throw new IllegalArgumentException("BiomeGraphJson: expected numeric field \"" + field + "\", got " + value);
        }
        return numberValue.intValue();
    }
}
