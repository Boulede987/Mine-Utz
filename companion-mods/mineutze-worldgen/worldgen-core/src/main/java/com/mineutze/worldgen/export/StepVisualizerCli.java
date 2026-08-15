package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.MeshGeometryRealization;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.TopologyFirstRegionSolver;

import java.nio.file.Path;

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
}
