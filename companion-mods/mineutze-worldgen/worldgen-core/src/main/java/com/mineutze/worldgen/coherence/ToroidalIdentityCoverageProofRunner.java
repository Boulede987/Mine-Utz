package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.util.Optional;
import java.util.Random;

/** Real, timed test of {@link ToroidalIdentityCoverageProof} against the real catalog - identity-only, no sizing at all. */
public final class ToroidalIdentityCoverageProofRunner {

    private static final int GRID_WIDTH = 16;
    private static final int GRID_HEIGHT = 16;
    private static final int BACKTRACK_BUDGET = 2_000_000;
    private static final long RANDOM_SEED = 2026091801L;

    private ToroidalIdentityCoverageProofRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);

        ToroidalGrid grid = new ToroidalGrid(GRID_WIDTH, GRID_HEIGHT);
        Random random = new Random(RANDOM_SEED);
        System.out.println("=== identity-only " + GRID_WIDTH + "x" + GRID_HEIGHT + " (" + grid.cellCount() + " cells) ===");
        long startNanos = System.nanoTime();
        Optional<ToroidalIdentityCoverageProof.BuildResult> result = ToroidalIdentityCoverageProof.tryBuild(table, grid, random, BACKTRACK_BUDGET);
        double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        if (result.get().assignment() != null) {
            System.out.println("SUCCEEDED in " + String.format("%.2f", elapsedSeconds) + "s, " + result.get().backtracksUsed() + " backtracks.");
        } else {
            System.out.println(
                "FAILED (exhausted " + result.get().backtracksUsed() + " backtracks, deepest fill count reached "
                    + result.get().deepestFillCountReached() + "/" + grid.cellCount() + ") after " + String.format("%.2f", elapsedSeconds) + "s"
            );
        }
    }
}
