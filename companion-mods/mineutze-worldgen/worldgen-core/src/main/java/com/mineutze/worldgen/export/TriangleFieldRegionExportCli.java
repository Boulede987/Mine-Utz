package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.ConnectedComponents;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.SeedRegionCache;
import com.mineutze.worldgen.regionsolve.TriangleFieldGeometryRealization;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First real visual of {@link TriangleFieldGeometryRealization} — the whole
 * glued-together region-solve path (seed placement → triangulation →
 * adjacency → fused CSP → adaptive field → rasterize), run against the
 * mod's REAL {@link ModBiomeGraph#DEFAULT}, not a synthetic test graph.
 *
 * <p>Runs at real {@link MacroGridConfig#DEFAULT} scale (~262k cells/region)
 * — measured at ~300ms with brute-force nearest-centroid rasterization
 * (no spatial index), fast enough that one was never needed, "measure
 * first, index later" same as this codebase's established precedent
 * ({@code MeshRasterizer}'s own history).
 *
 * <p>Also reports, per biome, its connected-component count (how many
 * separate patches of that biome exist — MULTIPLE disconnected patches of
 * the same biome is expected, not a bug: nothing about the CSP or field
 * makes a biome unique per region, same as real terrain) and each
 * component's approximate width (side of a same-area square, matching
 * {@link MacroGridConfig#cellCountForWidthBlocks}'s own area-not-bounding-box
 * convention) against that biome's declared {@link BiomeSizeRange} — the
 * field's additively-weighted-Voronoi rule targets that range, but never
 * guarantees it exactly (competing neighbors and the warp can both push a
 * component's real measured size off the target).
 *
 * <p>Usage: worldSeed outputDir regionsPerSide (all optional, positional) —
 * {@code regionsPerSide} solves an NxN grid of regions from (0,0) to
 * (N-1,N-1) and merges them into one combined image. Routed through a single
 * shared {@link SeedRegionCache}, solved in row-major order (not parallel) —
 * {@link TriangleFieldGeometryRealization} now consults already-solved
 * neighbors' boundary context for identity (see its own class doc), so a
 * region actually needs its earlier-in-order neighbors to already be solved
 * for that to have any effect. Solving all-parallel with no ordering (the
 * old behavior here) would show zero visible benefit from that fix — every
 * region would see every neighbor as unsolved, same as before.
 */
public final class TriangleFieldRegionExportCli {

    private static final long DEFAULT_WORLD_SEED = 42L;
    private static final String DEFAULT_OUTPUT_DIR = "triangle-field-export";
    private static final int DEFAULT_REGIONS_PER_SIDE = 1;
    private static final MacroGridConfig DEMO_GRID = new MacroGridConfig(4, 512);

    private TriangleFieldRegionExportCli() {
    }

    public static void main(String[] args) {
        long worldSeed = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_WORLD_SEED;
        Path outputDir = Path.of(args.length > 1 ? args[1] : DEFAULT_OUTPUT_DIR);
        int regionsPerSide = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_REGIONS_PER_SIDE;
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create output directory " + outputDir, e);
        }

        BiomeGraph graph = ModBiomeGraph.DEFAULT;
        List<RegionPos> regions = new ArrayList<>();
        for (int rx = 0; rx < regionsPerSide; rx++) {
            for (int rz = 0; rz < regionsPerSide; rz++) {
                regions.add(new RegionPos(rx, rz));
            }
        }

        System.out.printf(
            "Solving %d region(s) (%dx%d grid, worldSeed=%d, %d blocks/side each)...%n",
            regions.size(), regionsPerSide, regionsPerSide, worldSeed, DEMO_GRID.macroCellSizeBlocks() * DEMO_GRID.regionSizeCellsPerSide()
        );

        Map<MacroCellPos, BiomeId> combinedAssignment = new LinkedHashMap<>();
        SeedRegionCache cache = new SeedRegionCache(graph, DEMO_GRID, worldSeed);
        long startNanos = System.nanoTime();
        for (RegionPos region : regions) {
            long regionStartNanos = System.nanoTime();
            RegionSolveResult result = cache.resultFor(region);
            long regionDurationMillis = (System.nanoTime() - regionStartNanos) / 1_000_000;
            combinedAssignment.putAll(result.assignment());
            System.out.printf("  region %s solved in %d ms: %d cells%n", region, regionDurationMillis, result.assignment().size());
        }
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("Solved all %d region(s) in %d ms (wall clock): %d cells total%n", regions.size(), durationMillis, combinedAssignment.size());

        reportSizesAndComponents(combinedAssignment, graph, DEMO_GRID);

        // Evenly-spaced hues, not BiomeLegend's hash-based color (which put two of
        // ModBiomeGraph.DEFAULT's 6 real biomes close enough in hue to look identical in
        // the first render of this CLI) - at a small, known biome count, maximizing visual
        // separation matters more than BiomeLegend's own goal (a stable color regardless
        // of which subset of a much larger graph is present).
        Map<BiomeId, Integer> palette = evenlySpacedPalette(graph);
        Path pngFile = outputDir.resolve("regions_" + regionsPerSide + "x" + regionsPerSide + ".png");
        writeImage(combinedAssignment, pngFile, palette);
        System.out.println("wrote " + pngFile.toAbsolutePath());

        Path legendImageFile = outputDir.resolve("legend.png");
        writeLegendImage(graph, palette, legendImageFile);
        System.out.println("wrote " + legendImageFile.toAbsolutePath());
    }

    private static void reportSizesAndComponents(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph, MacroGridConfig gridConfig) {
        int cellSize = gridConfig.macroCellSizeBlocks();
        Map<BiomeId, List<Set<MacroCellPos>>> componentsByBiome = new LinkedHashMap<>();
        for (Set<MacroCellPos> component : ConnectedComponents.find(assignment, assignment.keySet())) {
            BiomeId biome = assignment.get(component.iterator().next());
            componentsByBiome.computeIfAbsent(biome, key -> new ArrayList<>()).add(component);
        }

        System.out.println("Per-biome patch count and size (target vs. measured, area-equivalent width):");
        componentsByBiome.forEach((biome, components) -> {
            BiomeSizeRange range = graph.node(biome).sizeRange();
            System.out.printf("  %s: %d separate patch(es), target %d-%d blocks%n", biome, components.size(), range.minWidthBlocks(), range.maxWidthBlocks());
            components.stream()
                .sorted((a, b) -> Integer.compare(b.size(), a.size()))
                .forEach(component -> {
                    double widthBlocks = Math.sqrt(component.size()) * cellSize;
                    boolean inRange = widthBlocks >= range.minWidthBlocks() && widthBlocks <= range.maxWidthBlocks();
                    System.out.printf(
                        "    patch: %d cells, ~%.0f blocks wide (area-equivalent) %s%n",
                        component.size(), widthBlocks, inRange ? "" : "- OUTSIDE target range"
                    );
                });
        });
    }

    private static Map<BiomeId, Integer> evenlySpacedPalette(BiomeGraph graph) {
        List<BiomeId> sortedIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        Map<BiomeId, Integer> palette = new LinkedHashMap<>();
        for (int i = 0; i < sortedIds.size(); i++) {
            float hue = (float) i / sortedIds.size();
            palette.put(sortedIds.get(i), Color.HSBtoRGB(hue, 0.75f, 0.9f) & 0xFFFFFF);
        }
        return palette;
    }

    private static void writeImage(Map<MacroCellPos, BiomeId> assignment, Path file, Map<BiomeId, Integer> palette) {
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

        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + file, e);
        }
    }

    /** An actual color swatch per biome, not just a hex code — reading a hex code back into a color isn't something most people can do by eye. */
    private static void writeLegendImage(BiomeGraph graph, Map<BiomeId, Integer> palette, Path file) {
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

        try {
            ImageIO.write(image, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing " + file, e);
        }
    }
}
