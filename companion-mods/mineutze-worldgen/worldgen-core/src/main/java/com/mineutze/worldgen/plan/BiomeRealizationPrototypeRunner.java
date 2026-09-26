package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Prototype of the step after the pattern search (2026-09-20): turn a solved 40x40 cell pattern
 * into a map with natural-looking borders, and CHECK the result rather than trust the theory.
 * For each warp strength it rasterizes the tile, verifies it ({@link BiomeMapVerifier}: every
 * region's real area against its biome's size range with a tolerance of 10% of the minimum, every
 * touching pair against the legality table, whether each cluster is still one region of about the
 * area of its cells) and writes a picture. The first strength is "none" - the raw hexagons - which
 * also shows how much error rasterizing alone introduces.
 */
public final class BiomeRealizationPrototypeRunner {

    private record Preset(String name, DivergenceFreeWarp.Settings settings) {
    }

    private static final double ATOM_WIDTH_BLOCKS = 300;
    private static final int OFF = Integer.MAX_VALUE;
    private static final int GRID_SIZE = 40;
    private static final double PERIMETER_RATIO = 1.3;
    private static final long WORLD_SEED = 1094L;
    private static final int SAMPLES_PER_SIDE = 1300;
    private static final double TOLERANCE_OF_MINIMUM = 0.10;
    private static final long WARP_SEED = 20260920L;
    private static final int FLOW_STEPS = 8;
    private static final Preset[] PRESETS = {
        new Preset("none", null),
        new Preset("one-step-22", new DivergenceFreeWarp.Settings(40, 40, 1_200, 22, 0.8)),
        new Preset("flow-22", new DivergenceFreeWarp.Settings(40, 40, 1_200, 22, 0.8, FLOW_STEPS)),
        new Preset("flow-35", new DivergenceFreeWarp.Settings(40, 40, 1_200, 35, 0.8, FLOW_STEPS)),
        new Preset("flow-55", new DivergenceFreeWarp.Settings(40, 40, 1_200, 55, 0.8, FLOW_STEPS)),
        new Preset("flow-80", new DivergenceFreeWarp.Settings(40, 40, 1_200, 80, 0.8, FLOW_STEPS)),
    };
    private static final DivergenceFreeWarp.Settings SEAM_DEMONSTRATION_SETTINGS = new DivergenceFreeWarp.Settings(40, 40, 1_200, 22, 0.8, FLOW_STEPS);
    private static final double WINDOW_LEFT = -5_000;
    private static final double WINDOW_TOP = -4_000;
    private static final double WINDOW_WIDTH = 21_000;
    private static final double WINDOW_HEIGHT = 15_000;
    private static final double WINDOW_BLOCKS_PER_PIXEL = 12;
    private static final int CROP_SCALE = 3;
    private static final int CROP_LEFT = 1_800;
    private static final int CROP_TOP = 1_000;
    private static final int CROP_WIDTH = 1_500;
    private static final int CROP_HEIGHT = 950;
    private static final Path OUTPUT_DIRECTORY = Path.of("build", "realization");

    private BiomeRealizationPrototypeRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        ToroidalGrid grid = new ToroidalGrid(GRID_SIZE, GRID_SIZE);
        Map<ToroidalPos, BiomeId> pattern = patternForWorldSeed(table, grid);
        PatternClusters clusters = PatternClusters.of(grid, pattern);
        HexTorusGeometry geometry = new HexTorusGeometry(GRID_SIZE, GRID_SIZE, ATOM_WIDTH_BLOCKS);
        List<BiomeId> biomeOrder = table.allBiomeIds();
        System.out.println("PROTOTYPE world seed " + WORLD_SEED + ": " + clusters.clusterCount() + " clusters in " + grid.cellCount() + " cells, tile " + String.format("%,.0f", geometry.tileVectorAX())
            + " blocks across, " + SAMPLES_PER_SIDE + "x" + SAMPLES_PER_SIDE + " samples (" + String.format("%.1f", geometry.tileVectorAX() / SAMPLES_PER_SIDE) + " blocks each)");

        for (Preset preset : PRESETS) {
            DivergenceFreeWarp warp = preset.settings() == null ? DivergenceFreeWarp.none(geometry) : new DivergenceFreeWarp(geometry, WARP_SEED, preset.settings());
            realize(preset, table, grid, pattern, clusters, geometry, biomeOrder, warp);
        }
        showTilesMeetingWithoutASeam(grid, pattern, geometry, biomeOrder);
    }

    /** Draws a window several tiles wide, once plain and once with the tile edges in red, to show by eye that tiles meet without a seam. */
    private static void showTilesMeetingWithoutASeam(
        ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern, HexTorusGeometry geometry, List<BiomeId> biomeOrder
    ) {
        DivergenceFreeWarp warp = new DivergenceFreeWarp(geometry, WARP_SEED, SEAM_DEMONSTRATION_SETTINGS);
        RealizedBiomeField field = new RealizedBiomeField(grid, pattern, geometry, warp, biomeOrder);
        Path plain = OUTPUT_DIRECTORY.resolve("world-window-plain.png");
        Path marked = OUTPUT_DIRECTORY.resolve("world-window-tile-edges-marked.png");
        PlaneWindowPngExporter.export(field, geometry, plain, WINDOW_LEFT, WINDOW_TOP, WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_BLOCKS_PER_PIXEL, false);
        PlaneWindowPngExporter.export(field, geometry, marked, WINDOW_LEFT, WINDOW_TOP, WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_BLOCKS_PER_PIXEL, true);
        System.out.println("PROTOTYPE seams: a " + (int) WINDOW_WIDTH + "x" + (int) WINDOW_HEIGHT + " block window of the world (several tiles): plain " + plain.toAbsolutePath()
            + " ; same view with tile edges in red " + marked.toAbsolutePath());
    }

    private static void realize(
        Preset preset, RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern, PatternClusters clusters, HexTorusGeometry geometry,
        List<BiomeId> biomeOrder, DivergenceFreeWarp warp
    ) {
        long startNanos = System.nanoTime();
        BiomeMap map = BiomeMapRasterizer.rasterize(grid, pattern, geometry, warp, SAMPLES_PER_SIDE, biomeOrder);
        BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, TOLERANCE_OF_MINIMUM, clusters, geometry.cellEdgeBlocks() * RealizationSelector.REALIZED_BORDER_FRACTION);
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        double[] distortion = mapDistortion(geometry, warp);

        String label = "PROTOTYPE " + preset.name() + ": ";
        System.out.println(label + "rasterized and verified in " + String.format("%.1fs", seconds) + "; local area scale of the map from " + String.format("%.2f", distortion[0])
            + " to " + String.format("%.2f", distortion[1]) + (distortion[0] <= 0 ? "  <-- FOLDS" : "") + "; stretch (longest axis of the map's ellipse) p99 " + String.format("%.2f", distortion[3])
            + ", max " + String.format("%.2f", distortion[2]));
        System.out.println(label + report.componentCount() + " regions for " + clusters.clusterCount() + " clusters; vanished " + report.vanishedClusters() + ", split "
            + report.splitClusters() + ", merged " + report.mergedComponents());
        System.out.println(label + "area violations (tolerance " + (int) (100 * TOLERANCE_OF_MINIMUM) + "% of the minimum): " + report.areaViolations().size() + " of " + report.componentCount());
        report.areaViolations().stream().limit(5).forEach(violation -> System.out.println(label + "  " + violation.biome() + ": " + String.format("%,.0f", violation.areaBlocks2())
            + " blocks^2, allowed " + String.format("%,.0f", violation.allowedMinBlocks2()) + " .. " + String.format("%,.0f", violation.allowedMaxBlocks2())));
        reportDrift(label, report.driftRatios());
        System.out.println(label + "illegal contacts: " + (report.illegalContactSamples().isEmpty() ? "none" : report.illegalContactSamples()) + " (legal contact samples: " + report.legalContactSamples() + ")");

        Path full = OUTPUT_DIRECTORY.resolve("realization-" + preset.name() + ".png");
        Path crop = OUTPUT_DIRECTORY.resolve("realization-" + preset.name() + "-zoom.png");
        BiomeMapPngExporter.exportFull(map, full, 1);
        BiomeMapPngExporter.exportRegion(map, crop, CROP_SCALE, CROP_LEFT, CROP_TOP, CROP_WIDTH, CROP_HEIGHT);
        System.out.println(label + "pictures: " + full.toAbsolutePath() + " and " + crop.toAbsolutePath());
    }

    private static void reportDrift(String label, double[] driftRatios) {
        double[] absolute = Arrays.stream(driftRatios).map(Math::abs).sorted().toArray();
        double mean = Arrays.stream(absolute).average().orElse(0);
        long beyondFivePercent = Arrays.stream(absolute).filter(drift -> drift > 0.05).count();
        long beyondTenPercent = Arrays.stream(absolute).filter(drift -> drift > 0.10).count();
        System.out.println(label + "area drift versus the cells each region came from: mean " + String.format("%.1f%%", 100 * mean) + ", p95 "
            + String.format("%.1f%%", 100 * absolute[(int) Math.min(absolute.length - 1, Math.floor(absolute.length * 0.95))]) + ", max " + String.format("%.1f%%", 100 * absolute[absolute.length - 1])
            + "; " + beyondFivePercent + " regions beyond 5%, " + beyondTenPercent + " beyond 10%");
    }

    /**
     * Local distortion of the sampling map x -> x + displacement(x), measured by finite differences so it
     * describes either warp mode: {smallest area scale, largest area scale, largest stretch, 99th percentile
     * stretch}. A smallest area scale at or below zero means the map folds onto itself. STRETCH is the
     * map's longest axis (largest singular value): 1 = no distortion, 4 = a shape is pulled 4 times longer
     * in one direction (and, the area being kept, squeezed to a quarter across) - how thin filaments would appear.
     */
    private static double[] mapDistortion(HexTorusGeometry geometry, DivergenceFreeWarp warp) {
        int side = 400;
        double probe = 0.5;
        double[] stretches = new double[side * side];
        double[] perRowMinimum = new double[side];
        double[] perRowMaximum = new double[side];
        IntStream.range(0, side).parallel().forEach(row -> computeRowDistortion(geometry, warp, probe, side, stretches, perRowMinimum, perRowMaximum, row));
        Arrays.sort(stretches);
        return new double[] {
            Arrays.stream(perRowMinimum).min().orElse(1), Arrays.stream(perRowMaximum).max().orElse(1), stretches[stretches.length - 1], stretches[(int) (stretches.length * 0.99)]
        };
    }

    private static void computeRowDistortion(
        HexTorusGeometry geometry, DivergenceFreeWarp warp, double probe, int side, double[] stretches, double[] perRowMinimum, double[] perRowMaximum, int row
    ) {
        double minimum = Double.MAX_VALUE;
        double maximum = -Double.MAX_VALUE;
        double v = (row + 0.5) / side;
        double[] right = new double[2];
        double[] left = new double[2];
        double[] up = new double[2];
        double[] down = new double[2];
        for (int column = 0; column < side; column++) {
            double determinant = processGridPoint(geometry, warp, probe, side, stretches, row, column, v, right, left, up, down);
            minimum = Math.min(minimum, determinant);
            maximum = Math.max(maximum, determinant);
        }
        perRowMinimum[row] = minimum;
        perRowMaximum[row] = maximum;
    }

    private static double processGridPoint(
        HexTorusGeometry geometry, DivergenceFreeWarp warp, double probe, int side, double[] stretches, int row, int column, double v, double[] right, double[] left, double[] up, double[] down
    ) {
        double u = (column + 0.5) / side;
        double x = u * geometry.tileVectorAX() + v * geometry.tileVectorBX();
        double y = u * geometry.tileVectorAY() + v * geometry.tileVectorBY();
        warp.displace(x + probe, y, right);
        warp.displace(x - probe, y, left);
        warp.displace(x, y + probe, up);
        warp.displace(x, y - probe, down);
        double a = 1 + (right[0] - left[0]) / (2 * probe);
        double b = (up[0] - down[0]) / (2 * probe);
        double c = (right[1] - left[1]) / (2 * probe);
        double d = 1 + (up[1] - down[1]) / (2 * probe);
        double determinant = a * d - b * c;
        double sumOfSquares = a * a + b * b + c * c + d * d;
        double longestAxis = Math.sqrt((sumOfSquares + Math.sqrt(Math.max(0, sumOfSquares * sumOfSquares - 4 * determinant * determinant))) / 2);
        stretches[row * side + column] = longestAxis;
        return determinant;
    }

    /** Reuses the solved pattern for this world seed if one was saved, else solves it with the real generator and saves it. */
    private static Map<ToroidalPos, BiomeId> patternForWorldSeed(RawRuleTable table, ToroidalGrid grid) {
        Path cache = OUTPUT_DIRECTORY.resolve("pattern-world-seed-" + WORLD_SEED + ".csv");
        if (Files.exists(cache)) {
            System.out.println("PROTOTYPE reusing the saved pattern " + cache.toAbsolutePath());
            return readPattern(cache, grid);
        }
        ClusterSizeModel model = new ClusterSizeModel(table, ATOM_WIDTH_BLOCKS, new ShapeRule(PERIMETER_RATIO, OFF, OFF, 2));
        ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(table, grid, model, WORLD_SEED, Runtime.getRuntime().availableProcessors());
        writePattern(cache, generated.pattern());
        System.out.println("PROTOTYPE solved world seed " + WORLD_SEED + " (attempt " + (generated.seedIndex() + 1) + ") and saved " + cache.toAbsolutePath());
        return generated.pattern();
    }

    private static void writePattern(Path file, Map<ToroidalPos, BiomeId> pattern) {
        List<String> lines = new ArrayList<>();
        pattern.forEach((pos, biome) -> lines.add(pos.q() + "," + pos.r() + "," + biome));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, lines);
        } catch (IOException failure) {
            throw new UncheckedIOException("BiomeRealizationPrototypeRunner: could not write " + file, failure);
        }
    }

    private static Map<ToroidalPos, BiomeId> readPattern(Path file, ToroidalGrid grid) {
        Map<ToroidalPos, BiomeId> pattern = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                parsePatternLine(pattern, line);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("BiomeRealizationPrototypeRunner: could not read " + file, failure);
        }
        if (pattern.size() != grid.cellCount()) {
            throw new IllegalStateException("BiomeRealizationPrototypeRunner: " + file + " holds " + pattern.size() + " cells, expected " + grid.cellCount());
        }
        return pattern;
    }

    private static void parsePatternLine(Map<ToroidalPos, BiomeId> pattern, String line) {
        String[] parts = line.split(",");
        String[] name = parts[2].split(":");
        pattern.put(new ToroidalPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), new BiomeId(name[0], name[1]));
    }
}
