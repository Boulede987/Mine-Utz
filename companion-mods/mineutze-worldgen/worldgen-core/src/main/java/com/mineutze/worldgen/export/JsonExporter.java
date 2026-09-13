package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Structured dump for program/LLM to reason over: graph that produced result (nodes +
 * adjacency rules, so illegal-looking adjacencies in cell data can be checked against
 * actual rules, not guessed) + every region's cell assignment and warnings.
 *
 * Hand-rolled, no JSON library - worldgen-core deliberately dependency-free (see
 * build.gradle), value shapes here are all primitives/strings anyway.
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
                .append(", \"blendWidthBlocks\": ").append(rule.blendWidthBlocks())
                .append(" }").append(i + 1 < rules.size() ? "," : "").append('\n');
        }
        out.append("    ]\n  }");
    }

    private static void writeRegions(StringBuilder out, ExportSnapshot snapshot) {
        MacroGridConfig grid = snapshot.gridConfig();
        List<RegionPos> regions = new java.util.ArrayList<>();
        for (int dx = 0; dx < snapshot.regionSpan(); dx++) {
            for (int dz = 0; dz < snapshot.regionSpan(); dz++) {
                regions.add(new RegionPos(snapshot.minRegion().x() + dx, snapshot.minRegion().z() + dz));
            }
        }

        out.append("  \"regions\": [\n");
        for (int i = 0; i < regions.size(); i++) {
            RegionPos region = regions.get(i);
            List<String> warnings = snapshot.warningsByRegion().getOrDefault(region, List.of());
            out.append("    {\n      \"x\": ").append(region.x()).append(", \"z\": ").append(region.z())
                .append(",\n      \"droppedDemandCount\": ").append(warnings.size())
                .append(",\n      \"warnings\": [");
            for (int w = 0; w < warnings.size(); w++) {
                out.append("\"").append(escape(warnings.get(w))).append("\"").append(w + 1 < warnings.size() ? ", " : "");
            }
            out.append("],\n      \"cells\": [\n");
            writeCells(out, snapshot, region);
            out.append("      ]\n    }").append(i + 1 < regions.size() ? "," : "").append('\n');
        }
        out.append("  ]");
    }

    /** Only cells snapshot has a biome for - real-world scan can have gaps (e.g. ungenerated chunk) a solved region never does. */
    private static void writeCells(StringBuilder out, ExportSnapshot snapshot, RegionPos region) {
        List<Map.Entry<MacroCellPos, BiomeId>> present = snapshot.gridConfig().cellsInRegion(region, snapshot.worldSeed()).stream()
            .sorted(java.util.Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .filter(cell -> snapshot.cells().containsKey(cell))
            .map(cell -> Map.entry(cell, snapshot.cells().get(cell)))
            .toList();
        for (int i = 0; i < present.size(); i++) {
            MacroCellPos cell = present.get(i).getKey();
            BiomeId biome = present.get(i).getValue();
            out.append("        { \"x\": ").append(cell.x()).append(", \"z\": ").append(cell.z())
                .append(", \"biome\": \"").append(escape(biome.toString())).append("\" }")
                .append(i + 1 < present.size() ? "," : "").append('\n');
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
