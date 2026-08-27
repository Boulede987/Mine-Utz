package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;

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

    private record Args(long worldSeed, int regionSpan, Path outputDir) {
    }

    private ExportCli() {
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        Args parsed = parseArgs(args);
        BiomeGraph graph = ModBiomeGraph.DEFAULT;
        MacroGridConfig gridConfig = MacroGridConfig.DEFAULT;
        RegionPos minRegion = new RegionPos(-parsed.regionSpan() / 2, -parsed.regionSpan() / 2);

        logSolveStart(parsed, gridConfig, minRegion);
        ExportSnapshot snapshot = solve(graph, gridConfig, parsed.worldSeed(), minRegion, parsed.regionSpan());
        writeAllFormats(parsed.outputDir(), snapshot, new BiomeLegend(graph));
    }

    private static Args parseArgs(String[] args) {
        return new Args(
            args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_WORLD_SEED,
            args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_REGION_SPAN,
            Path.of(args.length > 2 ? args[2] : DEFAULT_OUTPUT_DIR)
        );
    }

    private static void logSolveStart(Args args, MacroGridConfig gridConfig, RegionPos minRegion) {
        System.out.printf(
            "Solving %dx%d regions from %s (worldSeed=%d, macroCellSizeBlocks=%d, regionSizeCellsPerSide=%d)%n",
            args.regionSpan(), args.regionSpan(), minRegion, args.worldSeed(),
            gridConfig.macroCellSizeBlocks(), gridConfig.regionSizeCellsPerSide()
        );
    }

    private static ExportSnapshot solve(
        BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, RegionPos minRegion, int regionSpan
    ) {
        long solveStartNanos = System.nanoTime();
        ExportSnapshot snapshot = RegionGridSolver.solve(
            graph, gridConfig, worldSeed, minRegion, regionSpan,
            (region, result) -> System.out.printf(
                "  solved region %s: %d cells, %d dropped demand(s)%s%n",
                region, result.assignment().size(), result.droppedDemandCount(),
                result.warnings().isEmpty() ? "" : " — " + result.warnings().size() + " warning(s)"
            )
        );
        System.out.printf("Total solve time: %d ms%n", (System.nanoTime() - solveStartNanos) / 1_000_000);
        return snapshot;
    }

    private static void writeAllFormats(Path outputDir, ExportSnapshot snapshot, BiomeLegend legend) {
        ExportDirs.createOutputDir(outputDir);

        Path pngFile = outputDir.resolve("regions.png");
        Path asciiFile = outputDir.resolve("regions.txt");
        Path jsonFile = outputDir.resolve("regions.json");

        PngExporter.write(pngFile, snapshot, legend);
        AsciiExporter.write(asciiFile, snapshot, legend);
        JsonExporter.write(jsonFile, snapshot, legend);

        int totalDropped = snapshot.warningsByRegion().values().stream().mapToInt(List::size).sum();
        System.out.printf(
            "%nDone. %d region(s) solved, %d dropped demand(s) total.%n  %s%n  %s%n  %s%n",
            snapshot.warningsByRegion().size(), totalDropped,
            pngFile.toAbsolutePath(), asciiFile.toAbsolutePath(), jsonFile.toAbsolutePath()
        );
    }

}
