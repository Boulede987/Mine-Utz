package com.mineutze.worldgen.export.triangle;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.export.ExportDirs;
import com.mineutze.worldgen.regionsolve.ConnectedComponents;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionIntegrityValidator;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.SeedRegionCache;
import com.mineutze.worldgen.regionsolve.TriangleFieldGeometryRealization;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Visualizes {@link TriangleFieldGeometryRealization}'s real, live output against the
 * mod's actual {@link ModBiomeGraph#DEFAULT}, not a synthetic test graph — a PNG, a
 * legend, per-biome patch-size reporting, and an integrity check ({@link
 * RegionIntegrityValidator}), all against a real solve rather than needing to load a
 * world in-game to inspect the algorithm's own output.
 *
 * <p>Usage: worldSeed outputDir regionsPerSide (all optional, positional) —
 * {@code regionsPerSide} solves an NxN grid of regions from (0,0) to (N-1,N-1) and merges
 * them into one combined image. Routed through a single shared {@link SeedRegionCache},
 * solved in row-major order (not parallel) — {@link TriangleFieldGeometryRealization}
 * consults already-solved neighbors' boundary context for identity, so a region needs its
 * earlier-in-order neighbors already solved for that to have any effect; solving
 * all-parallel with no ordering would show zero benefit from that, since every region
 * would see every neighbor as unsolved.
 */
public final class TriangleFieldRegionExportCli {

    private static final long DEFAULT_WORLD_SEED = 42L;
    private static final String DEFAULT_OUTPUT_DIR = "triangle-field-export";
    private static final int DEFAULT_REGIONS_PER_SIDE = 1;
    private static final MacroGridConfig DEMO_GRID = new MacroGridConfig(4, 512);

    record Args(long worldSeed, Path outputDir, int regionsPerSide) {
    }

    private TriangleFieldRegionExportCli() {
    }

    public static void main(String[] args) {
        Args parsed = parseArgs(args);
        ExportDirs.createOutputDir(parsed.outputDir());

        BiomeGraph graph = ModBiomeGraph.DEFAULT;
        List<RegionPos> regions = buildRegionGrid(parsed.regionsPerSide());
        logSolveStart(parsed, regions);

        Map<MacroCellPos, BiomeId> combinedAssignment = solveAll(graph, parsed.worldSeed(), regions);

        reportSizesAndComponents(combinedAssignment, graph, DEMO_GRID);
        RegionIntegrityValidator.Report integrityReport = validateAndReport(combinedAssignment, graph);
        writeImages(combinedAssignment, graph, parsed.outputDir(), parsed.regionsPerSide());

        exitNonZeroIfUnclean(integrityReport);
    }

    static Args parseArgs(String[] args) {
        return new Args(
            args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_WORLD_SEED,
            Path.of(args.length > 1 ? args[1] : DEFAULT_OUTPUT_DIR),
            args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_REGIONS_PER_SIDE
        );
    }

    static List<RegionPos> buildRegionGrid(int regionsPerSide) {
        List<RegionPos> regions = new ArrayList<>();
        for (int rx = 0; rx < regionsPerSide; rx++) {
            for (int rz = 0; rz < regionsPerSide; rz++) {
                regions.add(new RegionPos(rx, rz));
            }
        }
        return regions;
    }

    private static void logSolveStart(Args args, List<RegionPos> regions) {
        System.out.printf(
            "Solving %d region(s) (%dx%d grid, worldSeed=%d, %d blocks/side each)...%n",
            regions.size(), args.regionsPerSide(), args.regionsPerSide(), args.worldSeed(),
            DEMO_GRID.macroCellSizeBlocks() * DEMO_GRID.regionSizeCellsPerSide()
        );
    }

    private static Map<MacroCellPos, BiomeId> solveAll(BiomeGraph graph, long worldSeed, List<RegionPos> regions) {
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
        return combinedAssignment;
    }

    private static RegionIntegrityValidator.Report validateAndReport(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        RegionIntegrityValidator.Report integrityReport = RegionIntegrityValidator.validate(assignment, graph);
        System.out.println();
        System.out.println(integrityReport.describe());
        return integrityReport;
    }

    private static void writeImages(Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph, Path outputDir, int regionsPerSide) {
        Map<BiomeId, Integer> palette = TriangleFieldPngExporter.evenlySpacedPalette(graph);

        Path pngFile = outputDir.resolve("regions_" + regionsPerSide + "x" + regionsPerSide + ".png");
        TriangleFieldPngExporter.writeRegionImage(assignment, pngFile, palette);
        System.out.println("wrote " + pngFile.toAbsolutePath());

        Path legendImageFile = outputDir.resolve("legend.png");
        TriangleFieldPngExporter.writeLegendImage(graph, palette, legendImageFile);
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

    // Same exit-nonzero-on-violations behavior validate_biome_export.py had, only AFTER
    // writing the images so a failing run still leaves something to look at.
    private static void exitNonZeroIfUnclean(RegionIntegrityValidator.Report integrityReport) {
        if (!integrityReport.isClean()) {
            System.exit(1);
        }
    }
}
