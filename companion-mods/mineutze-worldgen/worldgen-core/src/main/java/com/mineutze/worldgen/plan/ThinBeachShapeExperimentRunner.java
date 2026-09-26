package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measures the real impact of a per-biome perimeter FLOOR on beach's shape (2026-09-26): solves
 * the same world seed twice against the real catalog - once with beach's ordinary (blob-
 * permitting) shape rule, once with a floor that forces elongation - and reports each run's beach
 * cluster count, cell count and perimeter-per-cell (a compact blob's ratio shrinks toward 0 as it
 * grows; a real elongated strip's stays near 4, see the doc on {@code minimumPerimeter}'s straight-
 * line formula), plus a whole-pattern picture and a picture zoomed on one real beach cluster for a
 * direct visual comparison.
 *
 * <p>Beach's own declared minimum (300 blocks = exactly 1 cell at the production atom width) has
 * NO shape freedom at all - a single hexagon's perimeter is fixed, not a range - so any floor above
 * 1.0 is unsatisfiable there (the exact gap {@code DECISIONS.md}'s "known limits" already named).
 * This experiment widens beach's minimum to {@value #EXPERIMENT_BEACH_MIN_WIDTH_BLOCKS} blocks
 * ONLY here, never in the shipped catalog, to give the floor room to mean something. The real fix -
 * a construction-time check that a biome's own minimum can ever satisfy its own shape floor,
 * mirroring the border-requirement "could never be met" validation - is not built here.
 */
public final class ThinBeachShapeExperimentRunner {

    private static final long WORLD_SEED = 1094L;
    private static final BiomeId BEACH = new BiomeId("minecraft", "beach");
    private static final int EXPERIMENT_BEACH_MIN_WIDTH_BLOCKS = 600;
    private static final double FLOOR_RATIO = 1.25;
    private static final Path OUTPUT_DIRECTORY = Path.of("build", "thin-beach-shape");

    private ThinBeachShapeExperimentRunner() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData catalog = widenBeachMinimum(BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json"));
        RawRuleTable table = RawRuleTable.from(catalog);
        PlanSettings settings = PlanSettings.DEFAULT;
        ToroidalGrid grid = new ToroidalGrid(settings.gridSize(), settings.gridSize());
        int threads = Runtime.getRuntime().availableProcessors();
        System.out.println(
            "EXPERIMENT beach widened to " + EXPERIMENT_BEACH_MIN_WIDTH_BLOCKS + "-700 blocks for this run only (its real 300 minimum has zero shape freedom - a single cell's perimeter is fixed)"
        );

        ShapeRule defaultShape = new ShapeRule(settings.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2);
        ShapeRule flooredBeach = new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, FLOOR_RATIO, Integer.MAX_VALUE, Integer.MAX_VALUE, 2);

        run("baseline", table, grid, new ClusterSizeModel(table, settings.atomWidthBlocks(), defaultShape), threads);
        run("thin-beach", table, grid, new ClusterSizeModel(table, settings.atomWidthBlocks(), defaultShape, Map.of(BEACH, flooredBeach)), threads);
    }

    /** A copy of {@code catalog} with beach's own minimum raised; every other node, edge and group is untouched. */
    private static BiomeGraphData widenBeachMinimum(BiomeGraphData catalog) {
        List<NodeData> nodes = new ArrayList<>();
        for (NodeData node : catalog.nodes()) {
            nodes.add(widenIfBeach(node));
        }
        return new BiomeGraphData(nodes, catalog.edges(), catalog.groups());
    }

    private static NodeData widenIfBeach(NodeData node) {
        if (!BEACH.equals(new BiomeId(node.namespace(), node.path()))) {
            return node;
        }
        return new NodeData(node.namespace(), node.path(), EXPERIMENT_BEACH_MIN_WIDTH_BLOCKS, node.maxWidthBlocks(), node.selfAdjacencyLimit(), node.requirement());
    }

    private static void run(String label, RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, int threads) throws IOException {
        long start = System.nanoTime();
        ToroidalSizedPatternGenerator.Generated generated = ToroidalSizedPatternGenerator.generate(table, grid, model, WORLD_SEED, threads);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        Map<ToroidalPos, BiomeId> pattern = generated.pattern();
        System.out.println(label + ": solved at attempt " + (generated.seedIndex() + 1) + " in " + String.format(Locale.ROOT, "%.1f", seconds) + "s");

        List<List<ToroidalPos>> beachClusters = reportBeachShape(label, grid, pattern);

        Path full = OUTPUT_DIRECTORY.resolve("pattern-" + label + ".png");
        ToroidalPatternPngExporter.export(grid, pattern, table.allBiomeIds(), full);
        System.out.println(label + ": full pattern " + full.toAbsolutePath());

        List<ToroidalPos> clusterToZoomOn = tightestBoundingBoxCluster(beachClusters);
        if (clusterToZoomOn != null) {
            Path crop = OUTPUT_DIRECTORY.resolve("pattern-" + label + "-beach-zoom.png");
            exportCrop(full, clusterToZoomOn, crop);
            System.out.println(label + ": zoomed on one beach cluster (" + clusterToZoomOn.size() + " cells) " + crop.toAbsolutePath());
        }
    }

    /**
     * Whichever cluster's naive pixel bounding box is smallest, since a cluster that wraps the periodic tile's edge is drawn as several disconnected pieces far apart
     * on the flat canvas - its bounding box would span most of the image instead of the cluster's own real size. Null if there are no clusters at all.
     */
    private static List<ToroidalPos> tightestBoundingBoxCluster(List<List<ToroidalPos>> clusters) {
        List<ToroidalPos> tightest = null;
        long smallestArea = Long.MAX_VALUE;
        for (List<ToroidalPos> cluster : clusters) {
            long area = boundingBoxArea(cluster);
            if (area < smallestArea) {
                smallestArea = area;
                tightest = cluster;
            }
        }
        return tightest;
    }

    private static long boundingBoxArea(List<ToroidalPos> cluster) {
        int[] bounds = pixelBounds(cluster);
        return (long) (bounds[1] - bounds[0]) * (bounds[3] - bounds[2]);
    }

    /** {left, right, top, bottom} pixel bounds of {@code cluster}'s own hexagon centers. */
    private static int[] pixelBounds(List<ToroidalPos> cluster) {
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (ToroidalPos pos : cluster) {
            int x = ToroidalPatternPngExporter.centerXOf(pos);
            int y = ToroidalPatternPngExporter.centerYOf(pos);
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        return new int[] {left, right, top, bottom};
    }

    /** Reports beach's cluster count, total cells, total perimeter and perimeter-per-cell; returns every beach cluster's own cells (for picking one to zoom on). */
    private static List<List<ToroidalPos>> reportBeachShape(String label, ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern) {
        PatternClusters clusters = PatternClusters.of(grid, pattern);
        int[] clusterOfCell = clusters.clusterOfCell();
        int[] cellsInCluster = clusters.cellsInCluster();
        int[] perimeterOfCluster = new int[clusters.clusterCount()];
        BiomeId[] biomeOfCluster = new BiomeId[clusters.clusterCount()];
        List<List<ToroidalPos>> cellsOfCluster = new ArrayList<>();
        for (int cluster = 0; cluster < clusters.clusterCount(); cluster++) {
            cellsOfCluster.add(new ArrayList<>());
        }
        fillClusterShapes(grid, pattern, clusterOfCell, perimeterOfCluster, biomeOfCluster, cellsOfCluster);

        int totalBeachCells = 0;
        int totalBeachPerimeter = 0;
        List<List<ToroidalPos>> beachClusters = new ArrayList<>();
        for (int cluster = 0; cluster < clusters.clusterCount(); cluster++) {
            if (!BEACH.equals(biomeOfCluster[cluster])) {
                continue;
            }
            totalBeachCells += cellsInCluster[cluster];
            totalBeachPerimeter += perimeterOfCluster[cluster];
            beachClusters.add(cellsOfCluster.get(cluster));
        }
        double edgesPerCell = totalBeachCells == 0 ? 0 : (double) totalBeachPerimeter / totalBeachCells;
        System.out.println(
            label + ": beach - " + beachClusters.size() + " cluster(s), " + totalBeachCells + " total cells, " + totalBeachPerimeter + " total perimeter edges, "
                + String.format(Locale.ROOT, "%.2f", edgesPerCell) + " edges/cell (compact shrinks toward 0 as a cluster grows; a real strip stays near 4)"
        );
        for (List<ToroidalPos> cluster : beachClusters) {
            double clusterEdgesPerCell = (double) perimeterOf(grid, cluster) / cluster.size();
            System.out.println(label + ":   cluster of " + cluster.size() + " cells, " + String.format(Locale.ROOT, "%.2f", clusterEdgesPerCell) + " edges/cell");
        }
        return beachClusters;
    }

    private static int perimeterOf(ToroidalGrid grid, List<ToroidalPos> cluster) {
        Set<ToroidalPos> cells = new HashSet<>(cluster);
        int perimeter = 0;
        for (ToroidalPos pos : cluster) {
            for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                if (!cells.contains(neighbor)) {
                    perimeter++;
                }
            }
        }
        return perimeter;
    }

    private static void fillClusterShapes(
        ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern, int[] clusterOfCell, int[] perimeterOfCluster, BiomeId[] biomeOfCluster, List<List<ToroidalPos>> cellsOfCluster
    ) {
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            int cell = pos.r() * grid.width() + pos.q();
            int cluster = clusterOfCell[cell];
            biomeOfCluster[cluster] = pattern.get(pos);
            cellsOfCluster.get(cluster).add(pos);
            for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                int neighborCell = neighbor.r() * grid.width() + neighbor.q();
                if (clusterOfCell[neighborCell] != cluster) {
                    perimeterOfCluster[cluster]++;
                }
            }
        }
    }

    /** Crops {@code fullPicture} down to {@code cluster}'s own pixel bounds (plus a margin), using the same hexagon-center math the full picture was drawn with. */
    private static void exportCrop(Path fullPicture, List<ToroidalPos> cluster, Path cropPicture) throws IOException {
        int margin = ToroidalPatternPngExporter.HEX_RADIUS_PIXELS * 3;
        int[] bounds = pixelBounds(cluster);
        BufferedImage full = ImageIO.read(fullPicture.toFile());
        int cropLeft = Math.max(0, bounds[0] - margin);
        int cropTop = Math.max(0, bounds[2] - margin);
        int cropWidth = Math.min(full.getWidth() - cropLeft, bounds[1] - bounds[0] + 2 * margin);
        int cropHeight = Math.min(full.getHeight() - cropTop, bounds[3] - bounds[2] + 2 * margin);
        BufferedImage crop = full.getSubimage(cropLeft, cropTop, cropWidth, cropHeight);
        if (!ImageIO.write(crop, "png", cropPicture.toFile())) {
            throw new IllegalStateException("ThinBeachShapeExperimentRunner: no PNG writer available for " + cropPicture);
        }
    }
}
