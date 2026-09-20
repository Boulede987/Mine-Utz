package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.plan.WorldBiomePlan;

import java.io.IOException;
import java.util.List;

/**
 * The graph editor's "Solve check" button (2026-09-20): builds a real {@link WorldBiomePlan} - the very
 * pipeline a new world runs at creation - from THIS tool's saved graph ({@link GraphEditorFiles#DATA_FILE}),
 * for a few world seeds, and reports per seed whether it solved, how many pattern attempts and warp
 * strengths it took, and how long. Any seed that cannot be solved is listed with the pipeline's own
 * message and the process exits non-zero, so the editor shows a failed run.
 *
 * <p>A handful of seeds is evidence, not proof: a catalog that solves them all has not been shown to solve
 * every world seed. The 104-seed reliability study ({@code toroidalWorldSeedReliability}) is the heavier
 * check for that. What this button catches is the common editing mistake - a rule change that makes the
 * pattern unsolvable or a biome unable to reach its size - in minutes instead of at world creation.
 *
 * <p>Runs in its own JVM (see {@code GraphEditorServer#handleSolveCheck}), never on the HTTP server's
 * request threads: each seed uses every core for tens of seconds.
 */
final class GraphEditorSolveCheck {

    private static final List<Long> WORLD_SEEDS = List.of(1094L, 20260920L, -7311L, 424242L);

    private GraphEditorSolveCheck() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData catalog = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        int threads = Runtime.getRuntime().availableProcessors();
        System.out.println("Solve check of " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ": " + catalog.nodes().size() + " biomes, "
            + WORLD_SEEDS.size() + " world seeds, " + threads + " threads.");

        int failures = 0;
        for (long worldSeed : WORLD_SEEDS) {
            long start = System.nanoTime();
            try {
                WorldBiomePlan plan = WorldBiomePlan.create(catalog, worldSeed, threads, message -> System.out.println("  seed " + worldSeed + ": " + message));
                System.out.println("SOLVED seed " + worldSeed + " in " + secondsSince(start) + " s: pattern attempt " + plan.patternAttempts()
                    + ", warp strength " + plan.warpStrength() + " (" + plan.strengthsTried() + " tried)");
            } catch (RuntimeException failure) {
                failures++;
                System.out.println("FAILED seed " + worldSeed + " after " + secondsSince(start) + " s: " + failure.getMessage());
            }
        }

        System.out.println(failures == 0
            ? "All " + WORLD_SEEDS.size() + " world seeds solved."
            : failures + " of " + WORLD_SEEDS.size() + " world seeds could NOT be solved with this graph.");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String secondsSince(long startNanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - startNanos) / 1_000_000_000.0);
    }
}
