package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.GeometryRealization;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.MeshGeometryRealization;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.TopologyFirstRegionSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the region solver standalone — no Minecraft, no world, no ServerLevel
 * — and dumps the result as PNG (visual), ASCII (text), and JSON (structured,
 * for a program or an LLM to check adjacency/size claims against the actual
 * graph). Exists so the solver's own correctness (region shapes, seam
 * behavior, budget drops) can be inspected directly instead of only by
 * loading a world in-game.
 *
 * Usage: worldSeed regionSpan outputDir (all optional, positional).
 */
public final class ExportCli {

    private static final long DEFAULT_WORLD_SEED = 0L;
    private static final int DEFAULT_REGION_SPAN = 3;
    private static final String DEFAULT_OUTPUT_DIR = "worldgen-export";

    private ExportCli() {
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        long worldSeed = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_WORLD_SEED;
        int regionSpan = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_REGION_SPAN;
        Path outputDir = Path.of(args.length > 2 ? args[2] : DEFAULT_OUTPUT_DIR);

        BiomeGraph graph = ModBiomeGraph.DEFAULT;
        MacroGridConfig gridConfig = MacroGridConfig.DEFAULT;
        RegionPos minRegion = new RegionPos(-regionSpan / 2, -regionSpan / 2);

        // Comparison-only switch for the mesh-based geometry rewrite (doc/fix_dieas.md)
        // — same env-var-toggle precedent as MINEUTZE_SKIP_CLEANUP on GeometryRealization
        // itself. Never affects the live game/SeedRegionCache path, which always calls
        // GeometryRealization::realize directly.
        boolean useMeshGeometry = System.getenv("MINEUTZE_WORLDGEN_MESH_GEOMETRY") != null;
        TopologyFirstRegionSolver.GeometryRealizer geometryRealizer = useMeshGeometry
            ? MeshGeometryRealization::realize
            : GeometryRealization::realize;

        System.out.printf(
            "Solving %dx%d regions from %s (worldSeed=%d, macroCellSizeBlocks=%d, regionSizeCellsPerSide=%d, geometry=%s)%n",
            regionSpan, regionSpan, minRegion, worldSeed, gridConfig.macroCellSizeBlocks(), gridConfig.regionSizeCellsPerSide(),
            useMeshGeometry ? "mesh" : "pixel"
        );

        long solveStartNanos = System.nanoTime();
        ExportSnapshot snapshot = RegionGridSolver.solve(
            graph, gridConfig, worldSeed, minRegion, regionSpan,
            (region, result) -> System.out.printf(
                "  solved region %s: %d cells, %d dropped demand(s)%s%n",
                region, result.assignment().size(), result.droppedDemandCount(),
                result.warnings().isEmpty() ? "" : " — " + result.warnings().size() + " warning(s)"
            ),
            geometryRealizer
        );
        long solveDurationMillis = (System.nanoTime() - solveStartNanos) / 1_000_000;
        System.out.printf("Total solve time: %d ms%n", solveDurationMillis);

        createOutputDir(outputDir);
        BiomeLegend legend = new BiomeLegend(graph);

        Path pngFile = outputDir.resolve("regions.png");
        Path asciiFile = outputDir.resolve("regions.txt");
        Path jsonFile = outputDir.resolve("regions.json");

        PngExporter.write(pngFile, snapshot, legend);
        AsciiExporter.write(asciiFile, snapshot, legend);
        JsonExporter.write(jsonFile, snapshot, legend);

        int totalDropped = snapshot.warningsByRegion().values().stream().mapToInt(List::size).sum();
        System.out.printf(
            "%nDone. %d region(s) solved, %d dropped demand(s) total.%n  %s%n  %s%n  %s%n",
            snapshot.warningsByRegion().size(), totalDropped, pngFile.toAbsolutePath(), asciiFile.toAbsolutePath(), jsonFile.toAbsolutePath()
        );
    }

    private static void createOutputDir(Path outputDir) {
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create output directory " + outputDir, e);
        }
    }
}
