package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Structured dump for a program (or an LLM) to reason over: the graph that
 * produced the result (nodes + adjacency rules, so illegal-looking
 * adjacencies in the cell data can be checked against the actual rules
 * instead of guessed at) plus every region's cell assignment and warnings.
 *
 * Hand-rolled rather than pulling in a JSON library — worldgen-core is
 * deliberately dependency-free (see build.gradle), and the value shapes
 * here are all primitives/strings, nothing a library would meaningfully
 * simplify.
 */
public final class JsonExporter {

    private JsonExporter() {
    }

    public static void write(Path file, ExportSnapshot snapshot, BiomeLegend legend) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"worldSeed\": ").append(snapshot.worldSeed()).append(",\n");
        out.append("  \"grid\": { \"macroCellSizeBlocks\": ").append(snapshot.gridConfig().macroCellSizeBlocks())
            .append(", \"regionSizeCellsPerSide\": ").append(snapshot.gridConfig().regionSizeCellsPerSide()).append(" },\n");
        out.append("  \"minRegion\": { \"x\": ").append(snapshot.minRegion().x()).append(", \"z\": ").append(snapshot.minRegion().z()).append(" },\n");
        out.append("  \"regionSpan\": ").append(snapshot.regionSpan()).append(",\n");
        writeGraph(out, snapshot.graph(), legend);
        out.append(",\n");
        writeRegions(out, snapshot);
        out.append("\n}\n");

        try {
            Files.writeString(file, out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing JSON export to " + file, e);
        }
    }

    private static void writeGraph(StringBuilder out, BiomeGraph graph, BiomeLegend legend) {
        List<BiomeId> ids = graph.nodes().stream().map(BiomeNode::id).sorted().toList();

        out.append("  \"graph\": {\n    \"nodes\": [\n");
        for (int i = 0; i < ids.size(); i++) {
            BiomeNode node = graph.node(ids.get(i));
            out.append("      { \"id\": \"").append(escape(node.id().toString())).append("\"")
                .append(", \"glyph\": \"").append(legend.glyphOf(node.id())).append("\"")
                .append(", \"minWidthBlocks\": ").append(node.sizeRange().minWidthBlocks())
                .append(", \"maxWidthBlocks\": ").append(node.sizeRange().maxWidthBlocks())
                .append(", \"universalFiller\": ").append(node.universalFiller())
                .append(", \"roughness\": ").append(node.shapeHints().roughness())
                .append(", \"depthBias\": ").append(node.shapeHints().depthBias())
                .append(", \"flatness\": ").append(node.shapeHints().flatness())
                .append(", \"riversEnabled\": ").append(node.featureToggles().riversEnabled())
                .append(", \"lakesEnabled\": ").append(node.featureToggles().lakesEnabled())
                .append(", \"minSameBiomeNeighbors\": ").append(node.cohesionProfile().minSameBiomeNeighbors())
                .append(", \"directionalRunMajor\": ").append(node.cohesionProfile().directionalRunMajor())
                .append(", \"directionalRunMinor\": ").append(node.cohesionProfile().directionalRunMinor())
                .append(" }").append(i + 1 < ids.size() ? "," : "").append('\n');
        }
        out.append("    ],\n    \"rules\": [\n");

        List<AdjacencyRule> rules = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (graph.isAllowed(ids.get(i), ids.get(j))) {
                    rules.add(graph.rule(ids.get(i), ids.get(j)));
                }
            }
        }
        for (int i = 0; i < rules.size(); i++) {
            AdjacencyRule rule = rules.get(i);
            out.append("      { \"a\": \"").append(escape(rule.biomeA().toString())).append("\"")
                .append(", \"b\": \"").append(escape(rule.biomeB().toString())).append("\"")
                .append(", \"weight\": ").append(rule.weight())
                .append(", \"blendWidthBlocks\": ").append(rule.blendWidthBlocks())
                .append(" }").append(i + 1 < rules.size() ? "," : "").append('\n');
        }
        out.append("    ]\n  }");
    }

    private static void writeRegions(StringBuilder out, ExportSnapshot snapshot) {
        List<RegionPos> regions = snapshot.regionResults().keySet().stream()
            .sorted(java.util.Comparator.comparingInt(RegionPos::x).thenComparingInt(RegionPos::z))
            .toList();

        out.append("  \"regions\": [\n");
        for (int i = 0; i < regions.size(); i++) {
            RegionPos region = regions.get(i);
            RegionSolveResult result = snapshot.regionResults().get(region);
            out.append("    {\n      \"x\": ").append(region.x()).append(", \"z\": ").append(region.z())
                .append(",\n      \"droppedDemandCount\": ").append(result.droppedDemandCount())
                .append(",\n      \"warnings\": [");
            List<String> warnings = result.warnings();
            for (int w = 0; w < warnings.size(); w++) {
                out.append("\"").append(escape(warnings.get(w))).append("\"").append(w + 1 < warnings.size() ? ", " : "");
            }
            out.append("],\n      \"cells\": [\n");
            writeCells(out, result);
            out.append("      ]\n    }").append(i + 1 < regions.size() ? "," : "").append('\n');
        }
        out.append("  ]");
    }

    private static void writeCells(StringBuilder out, RegionSolveResult result) {
        List<Map.Entry<MacroCellPos, BiomeId>> cells = result.assignment().entrySet().stream()
            .sorted(Map.Entry.<MacroCellPos, BiomeId>comparingByKey(
                java.util.Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z)))
            .toList();
        for (int i = 0; i < cells.size(); i++) {
            Map.Entry<MacroCellPos, BiomeId> entry = cells.get(i);
            out.append("        { \"x\": ").append(entry.getKey().x()).append(", \"z\": ").append(entry.getKey().z())
                .append(", \"biome\": \"").append(escape(entry.getValue().toString())).append("\" }")
                .append(i + 1 < cells.size() ? "," : "").append('\n');
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
