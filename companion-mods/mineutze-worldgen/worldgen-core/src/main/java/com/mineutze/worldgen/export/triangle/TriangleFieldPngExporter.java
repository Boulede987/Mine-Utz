package com.mineutze.worldgen.export.triangle;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.MacroCellPos;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TriangleFieldPngExporter {

    private TriangleFieldPngExporter() {
    }

    /**
     * Evenly-spaced hues, not {@code BiomeLegend}'s hash-based color (put two of {@code
     * ModBiomeGraph.DEFAULT}'s 6 real biomes close enough in hue to look identical) - at
     * this small known biome count, maximizing visual separation matters more than {@code
     * BiomeLegend}'s own goal (stable color regardless of which subset of a much larger
     * graph is present).
     */
    static Map<BiomeId, Integer> evenlySpacedPalette(BiomeGraph graph) {
        List<BiomeId> sortedIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        Map<BiomeId, Integer> palette = new LinkedHashMap<>();
        for (int i = 0; i < sortedIds.size(); i++) {
            float hue = (float) i / sortedIds.size();
            palette.put(sortedIds.get(i), Color.HSBtoRGB(hue, 0.75f, 0.9f) & 0xFFFFFF);
        }
        return palette;
    }

    static void writeRegionImage(Map<MacroCellPos, BiomeId> assignment, Path file, Map<BiomeId, Integer> palette) {
        int minX = assignment.keySet().stream().mapToInt(MacroCellPos::x).min().orElseThrow();
        int minZ = assignment.keySet().stream().mapToInt(MacroCellPos::z).min().orElseThrow();
        int maxX = assignment.keySet().stream().mapToInt(MacroCellPos::x).max().orElseThrow();
        int maxZ = assignment.keySet().stream().mapToInt(MacroCellPos::z).max().orElseThrow();
        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            int px = entry.getKey().x() - minX;
            int pz = entry.getKey().z() - minZ;
            image.setRGB(px, pz, palette.get(entry.getValue()));
        }

        writePng(image, file);
    }

    /** Actual color swatch per biome, not just a hex code - most people can't read a hex code back into a color by eye. */
    static void writeLegendImage(BiomeGraph graph, Map<BiomeId, Integer> palette, Path file) {
        List<BiomeId> sortedIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();

        int swatchSize = 32;
        int rowHeight = 44;
        int padding = 14;
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 18);

        BufferedImage measuring = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        FontMetrics metrics = measuring.createGraphics().getFontMetrics(font);
        int maxLabelWidth = sortedIds.stream().mapToInt(id -> metrics.stringWidth(id.toString())).max().orElse(0);

        int width = padding * 3 + swatchSize + maxLabelWidth;
        int height = padding * 2 + sortedIds.size() * rowHeight;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setFont(font);

        int y = padding;
        for (BiomeId id : sortedIds) {
            g.setColor(new Color(palette.get(id)));
            g.fillRect(padding, y, swatchSize, swatchSize);
            g.setColor(Color.BLACK);
            g.drawRect(padding, y, swatchSize, swatchSize);
            g.drawString(id.toString(), padding * 2 + swatchSize, y + swatchSize - metrics.getDescent());
            y += rowHeight;
        }
        g.dispose();

        writePng(image, file);
    }

    private static void writePng(BufferedImage image, Path file) {
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + file, e);
        }
    }
}
