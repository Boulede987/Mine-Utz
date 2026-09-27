package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BiomeId;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tests the multigrid hypothesis (2026-09-27, see {@code DECISIONS.md}) across several world seeds:
 * solve at production's own proven scale (40x40 cells, 300-block atoms), then upsample that solution
 * into a seed for the fine 120x120/100-block grid instead of starting fine resolution from a random
 * Voronoi partition. {@code gridSize}/{@code atomWidthBlocks} were chosen so a coarse cell aggregates
 * an exact 3x3 block of fine cells - the coarse solve IS today's production solve, unmodified.
 *
 * <p>The fine refinement pass retries with a different internal random seed (same structural,
 * coarse-derived seed) up to {@value #MAX_REFINEMENT_ATTEMPTS} times, taking the first attempt that
 * solves - the same "try several independent attempts, take whichever solves first" mechanism
 * production already uses at the coarse level ({@link ParallelSeedSearch}), just applied one level
 * deeper. Found necessary directly: one world seed (-7311) plateaued at cost 4 with attempt 0 alone,
 * unmoved by DOUBLING the step budget (20,000,000 -> 40,000,000, identical cost both times - a local
 * trap for that one random walk, not a global slowdown) but solved in 3.7s on the very next attempt.
 *
 * <p>The FINE model gives beach a real perimeter floor ({@value #BEACH_FLOOR_RATIO}x, forcing
 * elongation - see {@code ShapeRule#minPerimeterRatio}), the original goal this whole investigation
 * started from. Unlike {@code ThinBeachShapeExperimentRunner}'s earlier attempt at production's
 * 300-block atom width, beach's REAL declared range (300-700 blocks) already gives 9-49 cells at this
 * finer 100-block atom - no widened minimum needed this time. The coarse model is untouched (no
 * floor), matching "the coarse solve IS production, unmodified" - only fine refinement has to satisfy
 * it, on top of fixing the blocky coarse-cell seams.
 */
public final class MultigridSeedPrototypeRunner {

    private static final long[] WORLD_SEEDS = {42L, 1094L, 20260920L, -7311L, 424242L};
    private static final int COARSE_GRID_SIZE = 40;
    private static final double COARSE_ATOM_WIDTH_BLOCKS = 300.0;
    private static final int FINE_GRID_SIZE = 120;
    private static final double FINE_ATOM_WIDTH_BLOCKS = 100.0;
    private static final int AGGREGATION_FACTOR = FINE_GRID_SIZE / COARSE_GRID_SIZE;
    private static final long REFINEMENT_STEPS = 20_000_000L;
    private static final int MAX_REFINEMENT_ATTEMPTS = 5;
    private static final ToroidalSizedLocalSearch.Schedule REFINEMENT_SCHEDULE = new ToroidalSizedLocalSearch.Schedule(0.40, 0.20, 9_000_000);
    private static final Path OUTPUT_DIRECTORY = Path.of("build", "multigrid-seed-prototype");
    private static final BiomeId BEACH = new BiomeId("minecraft", "beach");
    /** Safe up to ~1.727 even at beach's own tightest case (9 cells, its real declared minimum at this atom width) - see DECISIONS.md. */
    private static final double BEACH_FLOOR_RATIO = 1.5;

    private record RefinementOutcome(boolean solved, int attemptsUsed, int bestCost, Map<ToroidalPos, BiomeId> assignment) {
    }

    private record SeedResult(long worldSeed, double coarseSeconds, int seededCost, RefinementOutcome refinement, double refineSeconds) {
    }

    private MultigridSeedPrototypeRunner() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData catalog = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(catalog);
        int threads = Runtime.getRuntime().availableProcessors();
        ToroidalGrid coarseGrid = new ToroidalGrid(COARSE_GRID_SIZE, COARSE_GRID_SIZE);
        ToroidalGrid fineGrid = new ToroidalGrid(FINE_GRID_SIZE, FINE_GRID_SIZE);
        ClusterSizeModel coarseModel = coarseModel(table);
        ClusterSizeModel fineModel = fineModel(table);

        System.out.println(
            "MULTIGRID " + WORLD_SEEDS.length + " world seeds: coarse " + COARSE_GRID_SIZE + "x" + COARSE_GRID_SIZE + "@" + COARSE_ATOM_WIDTH_BLOCKS
                + " blocks (production, unmodified) -> fine " + FINE_GRID_SIZE + "x" + FINE_GRID_SIZE + "@" + FINE_ATOM_WIDTH_BLOCKS
                + " blocks, aggregation factor " + AGGREGATION_FACTOR + ", refinement budget " + REFINEMENT_STEPS + " steps x up to " + MAX_REFINEMENT_ATTEMPTS + " attempts"
        );

        List<SeedResult> results = new ArrayList<>();
        for (long worldSeed : WORLD_SEEDS) {
            results.add(runSeed(table, coarseGrid, fineGrid, coarseModel, fineModel, worldSeed, threads));
        }
        reportSummary(results);
    }

    private static SeedResult runSeed(
        RawRuleTable table, ToroidalGrid coarseGrid, ToroidalGrid fineGrid, ClusterSizeModel coarseModel, ClusterSizeModel fineModel, long worldSeed, int threads
    ) throws IOException {
        System.out.println();
        System.out.println("SEED " + worldSeed);
        long coarseStart = System.nanoTime();
        ToroidalSizedPatternGenerator.Generated coarseSolved = ToroidalSizedPatternGenerator.generate(table, coarseGrid, coarseModel, worldSeed, threads);
        double coarseSeconds = secondsSince(coarseStart);
        System.out.println("  coarse solved at attempt " + (coarseSolved.seedIndex() + 1) + " in " + String.format(Locale.ROOT, "%.1f", coarseSeconds) + "s");

        Map<ToroidalPos, BiomeId> seed = upsampleToFineSeed(fineGrid, coarseGrid, coarseSolved.pattern());
        int seededCost = ToroidalSizedLocalSearch.cost(table, fineGrid, fineModel, seed);
        System.out.println("  seeded (before refinement) cost: " + seededCost);

        long refineStart = System.nanoTime();
        RefinementOutcome outcome = refineWithRetries(table, fineGrid, fineModel, worldSeed, seed);
        double refineSeconds = secondsSince(refineStart);
        System.out.println(
            "  refined: " + (outcome.solved() ? "SOLVED (attempt " + outcome.attemptsUsed() + ")" : "not solved after " + outcome.attemptsUsed() + " attempts, best cost "
                + outcome.bestCost()) + " in " + String.format(Locale.ROOT, "%.1f", refineSeconds) + "s"
        );
        exportPictures(table, coarseGrid, fineGrid, worldSeed, coarseSolved.pattern(), seed, outcome.assignment());
        return new SeedResult(worldSeed, coarseSeconds, seededCost, outcome, refineSeconds);
    }

    private static void exportPictures(
        RawRuleTable table, ToroidalGrid coarseGrid, ToroidalGrid fineGrid, long worldSeed, Map<ToroidalPos, BiomeId> coarsePattern, Map<ToroidalPos, BiomeId> seed,
        Map<ToroidalPos, BiomeId> refined
    ) throws IOException {
        List<BiomeId> colorOrder = table.allBiomeIds();
        ToroidalPatternPngExporter.export(coarseGrid, coarsePattern, colorOrder, OUTPUT_DIRECTORY.resolve("seed-" + worldSeed + "-1-coarse.png"));
        ToroidalPatternPngExporter.export(fineGrid, seed, colorOrder, OUTPUT_DIRECTORY.resolve("seed-" + worldSeed + "-2-seeded.png"));
        if (refined != null) {
            Path refinedPicture = OUTPUT_DIRECTORY.resolve("seed-" + worldSeed + "-3-refined.png");
            ToroidalPatternPngExporter.export(fineGrid, refined, colorOrder, refinedPicture);
            exportBeachZoom(fineGrid, refined, refinedPicture, worldSeed);
        }
        System.out.println("  pictures written to " + OUTPUT_DIRECTORY.toAbsolutePath());
    }

    /** Zooms on beach's own tightest (non-wraparound) cluster in the refined picture, so its real shape can be inspected directly. */
    private static void exportBeachZoom(ToroidalGrid fineGrid, Map<ToroidalPos, BiomeId> refined, Path refinedPicture, long worldSeed) throws IOException {
        List<List<ToroidalPos>> beachClusters = beachClustersOf(fineGrid, refined);
        List<ToroidalPos> tightest = tightestBoundingBoxCluster(beachClusters);
        if (tightest == null) {
            System.out.println("  no beach cluster found to zoom on");
            return;
        }
        Path crop = OUTPUT_DIRECTORY.resolve("seed-" + worldSeed + "-4-beach-zoom.png");
        exportCrop(refinedPicture, tightest, crop);
        System.out.println("  beach zoom (" + tightest.size() + " cells) written to " + crop.toAbsolutePath());
    }

    private static List<List<ToroidalPos>> beachClustersOf(ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern) {
        PatternClusters clusters = PatternClusters.of(grid, pattern);
        int[] clusterOfCell = clusters.clusterOfCell();
        BiomeId[] biomeOfCluster = new BiomeId[clusters.clusterCount()];
        List<List<ToroidalPos>> cellsOfCluster = new ArrayList<>();
        for (int cluster = 0; cluster < clusters.clusterCount(); cluster++) {
            cellsOfCluster.add(new ArrayList<>());
        }
        for (ToroidalPos pos : grid.allPositionsInFillOrder()) {
            int cell = pos.r() * grid.width() + pos.q();
            int cluster = clusterOfCell[cell];
            biomeOfCluster[cluster] = pattern.get(pos);
            cellsOfCluster.get(cluster).add(pos);
        }
        List<List<ToroidalPos>> beachClusters = new ArrayList<>();
        for (int cluster = 0; cluster < clusters.clusterCount(); cluster++) {
            if (BEACH.equals(biomeOfCluster[cluster])) {
                beachClusters.add(cellsOfCluster.get(cluster));
            }
        }
        return beachClusters;
    }

    /** Whichever cluster's naive pixel bounding box is smallest, since a cluster that wraps the periodic tile's edge is drawn as several disconnected pieces far apart on the flat canvas. */
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
            throw new IllegalStateException("MultigridSeedPrototypeRunner: no PNG writer available for " + cropPicture);
        }
    }

    /** Tries a different internal random seed (the same structural, coarse-derived seed each time) up to {@link #MAX_REFINEMENT_ATTEMPTS} times, taking the first that solves. */
    private static RefinementOutcome refineWithRetries(RawRuleTable table, ToroidalGrid fineGrid, ClusterSizeModel fineModel, long worldSeed, Map<ToroidalPos, BiomeId> seed) {
        int bestCostSoFar = Integer.MAX_VALUE;
        for (int attempt = 0; attempt < MAX_REFINEMENT_ATTEMPTS; attempt++) {
            ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.searchFromSeed(
                table, fineGrid, fineModel, worldSeed + attempt, seed, REFINEMENT_STEPS, REFINEMENT_SCHEDULE, () -> false, ToroidalSizedLocalSearch.DEFAULT_RELABEL_PROBABILITY
            );
            if (result.assignment() != null) {
                return new RefinementOutcome(true, attempt + 1, 0, result.assignment());
            }
            bestCostSoFar = Math.min(bestCostSoFar, result.bestCost());
        }
        return new RefinementOutcome(false, MAX_REFINEMENT_ATTEMPTS, bestCostSoFar, null);
    }

    private static Map<ToroidalPos, BiomeId> upsampleToFineSeed(ToroidalGrid fineGrid, ToroidalGrid coarseGrid, Map<ToroidalPos, BiomeId> coarseSolution) {
        Map<ToroidalPos, BiomeId> seed = new LinkedHashMap<>();
        for (ToroidalPos finePos : fineGrid.allPositionsInFillOrder()) {
            assignUpsampledCell(seed, coarseGrid, coarseSolution, finePos);
        }
        return seed;
    }

    private static void assignUpsampledCell(Map<ToroidalPos, BiomeId> seed, ToroidalGrid coarseGrid, Map<ToroidalPos, BiomeId> coarseSolution, ToroidalPos finePos) {
        ToroidalPos coarsePos = coarseGrid.normalize(finePos.q() / AGGREGATION_FACTOR, finePos.r() / AGGREGATION_FACTOR);
        BiomeId biome = coarseSolution.get(coarsePos);
        if (biome == null) {
            throw new IllegalStateException("MultigridSeedPrototypeRunner: coarse solution has no biome for " + coarsePos + " (fine cell " + finePos + ")");
        }
        seed.put(finePos, biome);
    }

    private static void reportSummary(List<SeedResult> results) {
        System.out.println();
        long solvedCount = results.stream().filter(result -> result.refinement().solved()).count();
        System.out.println("RESULT " + solvedCount + " of " + results.size() + " world seeds fully solved (cost 0) within the refinement budget");
        for (SeedResult result : results) {
            reportSeedLine(result);
        }
        double totalSeconds = results.stream().mapToDouble(result -> result.coarseSeconds() + result.refineSeconds()).sum();
        System.out.println(
            "RESULT total wall time across all " + results.size() + " seeds: " + String.format(Locale.ROOT, "%.1f", totalSeconds) + "s ("
                + String.format(Locale.ROOT, "%.1f", totalSeconds / results.size()) + "s average per world)"
        );
    }

    private static void reportSeedLine(SeedResult result) {
        RefinementOutcome outcome = result.refinement();
        System.out.println(
            "RESULT   seed " + result.worldSeed() + ": " + (outcome.solved() ? "SOLVED (attempt " + outcome.attemptsUsed() + ")" : "NOT solved (best cost " + outcome.bestCost() + ")")
                + ", seeded cost " + result.seededCost() + ", refinement " + String.format(Locale.ROOT, "%.1f", result.refineSeconds()) + "s, coarse "
                + String.format(Locale.ROOT, "%.1f", result.coarseSeconds()) + "s"
        );
    }

    private static ClusterSizeModel coarseModel(RawRuleTable table) {
        return new ClusterSizeModel(table, COARSE_ATOM_WIDTH_BLOCKS, new ShapeRule(PlanSettings.DEFAULT.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2));
    }

    private static ClusterSizeModel fineModel(RawRuleTable table) {
        ShapeRule defaultShape = new ShapeRule(PlanSettings.DEFAULT.perimeterRatio(), Integer.MAX_VALUE, Integer.MAX_VALUE, 2);
        ShapeRule flooredBeach = new ShapeRule(ShapeRule.NO_PERIMETER_LIMIT, BEACH_FLOOR_RATIO, Integer.MAX_VALUE, Integer.MAX_VALUE, 2);
        return new ClusterSizeModel(table, FINE_ATOM_WIDTH_BLOCKS, defaultShape, Map.of(BEACH, flooredBeach));
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }
}
