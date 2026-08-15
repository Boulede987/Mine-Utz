package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.MeshGeometryRealization;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.TopologyFirstRegionSolver;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Same solve as {@link ExportCli}, but instead of writing the finished
 * result, dumps a numbered PNG per {@link MeshGeometryRealization.StageListener}
 * callback into a subfolder per region — seed graph, scatter, triangulation,
 * growth, gap-fill, size enforcement, displacement, rasterize, pixel repair,
 * final. Built to let the pipeline actually be WATCHED stage by stage rather
 * than only ever inspected at the end.
 *
 * <p>Reuses {@link RegionGridSolver}'s existing per-region driving loop
 * unchanged — the only difference from {@link ExportCli} is which {@link
 * TopologyFirstRegionSolver.GeometryRealizer} gets passed in: a fresh {@link
 * StepVisualizerListener} constructed per region (using {@code
 * RegionSolveInput#region} to pick its own subfolder) wrapping the exact
 * same {@link MeshGeometryRealization#realize(com.mineutze.worldgen.regionsolve.topology.SeedGraph,
 * java.util.Map, com.mineutze.worldgen.regionsolve.RegionSolveInput,
 * MeshGeometryRealization.StageListener)} call the live game/export path
 * uses — no separate copy of the pipeline to keep in sync.
 *
 * Usage: worldSeed regionSpan outputDir (all optional, positional).
 */
public final class StepVisualizerCli {

    private static final long DEFAULT_WORLD_SEED = 0L;
    private static final int DEFAULT_REGION_SPAN = 3;
    private static final String DEFAULT_OUTPUT_DIR = "worldgen-steps";

    private StepVisualizerCli() {
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        long worldSeed = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_WORLD_SEED;
        int regionSpan = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_REGION_SPAN;
        Path outputDir = Path.of(args.length > 2 ? args[2] : DEFAULT_OUTPUT_DIR);

        BiomeGraph graph = ModBiomeGraph.DEFAULT;
        MacroGridConfig gridConfig = MacroGridConfig.DEFAULT;
        RegionPos minRegion = new RegionPos(-regionSpan / 2, -regionSpan / 2);
        BiomeLegend legend = new BiomeLegend(graph);
        writeLegend(outputDir, graph, legend);
        writeLegendImage(outputDir, graph, legend);

        System.out.printf(
            "Visualizing %dx%d regions from %s (worldSeed=%d) into %s%n",
            regionSpan, regionSpan, minRegion, worldSeed, outputDir.toAbsolutePath()
        );

        TopologyFirstRegionSolver.GeometryRealizer visualizingRealizer = (seedGraph, assignedIdentities, input) -> {
            Path regionDir = outputDir.resolve("region_" + input.region().x() + "_" + input.region().z());
            StepVisualizerListener listener = new StepVisualizerListener(regionDir, legend);
            return MeshGeometryRealization.realize(seedGraph, assignedIdentities, input, listener);
        };

        long solveStartNanos = System.nanoTime();
        RegionGridSolver.solve(
            graph, gridConfig, worldSeed, minRegion, regionSpan,
            (region, result) -> System.out.printf(
                "  visualized region %s: %d cells, %d dropped demand(s)%n",
                region, result.assignment().size(), result.droppedDemandCount()
            ),
            visualizingRealizer
        );
        long solveDurationMillis = (System.nanoTime() - solveStartNanos) / 1_000_000;

        System.out.printf("%nDone in %d ms. Stage PNGs written under %s, one subfolder per region.%n", solveDurationMillis, outputDir.toAbsolutePath());
    }

    /** One legend.txt at the output root (not per-region — the color/glyph mapping is the same for every region in one run) so a PNG's colors can actually be read back into biome names. */
    private static void writeLegend(Path outputDir, BiomeGraph graph, BiomeLegend legend) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create output directory " + outputDir, e);
        }

        StringBuilder out = new StringBuilder("Legend (biome -> color):\n");
        for (BiomeNode node : graph.nodes().stream().sorted(Comparator.comparing(BiomeNode::id)).toList()) {
            BiomeId id = node.id();
            out.append(String.format("  %-2s %-24s #%06X%n", legend.glyphOf(id), id, legend.rgbOf(id)));
        }

        try {
            Files.writeString(outputDir.resolve("legend.txt"), out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing legend.txt to " + outputDir, e);
        }
    }

    /** Same data as {@link #writeLegend}, as an actual color swatch per biome — reading a hex code back into a color isn't something most people can do by eye. */
    private static void writeLegendImage(Path outputDir, BiomeGraph graph, BiomeLegend legend) {
        List<BiomeNode> nodes = graph.nodes().stream().sorted(Comparator.comparing(BiomeNode::id)).toList();

        int swatchSize = 32;
        int rowHeight = 44;
        int padding = 14;
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 18);

        BufferedImage measuring = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        FontMetrics metrics = measuring.createGraphics().getFontMetrics(font);
        int maxLabelWidth = nodes.stream().mapToInt(node -> metrics.stringWidth(node.id().toString())).max().orElse(0);

        int width = padding * 3 + swatchSize + maxLabelWidth;
        int height = padding * 2 + nodes.size() * rowHeight;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setFont(font);

        int y = padding;
        for (BiomeNode node : nodes) {
            BiomeId id = node.id();
            g.setColor(new Color(legend.rgbOf(id)));
            g.fillRect(padding, y, swatchSize, swatchSize);
            g.setColor(Color.BLACK);
            g.drawRect(padding, y, swatchSize, swatchSize);
            g.drawString(id.toString(), padding * 2 + swatchSize, y + swatchSize - metrics.getDescent());
            y += rowHeight;
        }
        g.dispose();

        Path file = outputDir.resolve("legend.png");
        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing legend.png to " + file, e);
        }
    }
}
