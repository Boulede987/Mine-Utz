package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real, computed answer to "how far could circumference scale" (2026-09-17 user question) -
 * runs {@link PeriodicCoherenceProof}'s own real search at several real {@code
 * maxCellsPerRow} settings (now that cell-level backtracking can actually help long rows,
 * see that class's own doc) against the REAL current catalog, reporting the ACTUAL achieved
 * circumference at each setting - not an estimate.
 *
 * <p>rowCount is held small and fixed throughout (10) - vertical scaling is already cheap
 * and well-understood (just stack more rows); this experiment isolates the genuinely hard
 * dimension, circumference, a direct function of how many cells one row can hold.
 */
public final class WidthScalingExperiment {

    private static final Path REPORT_PATH = Path.of("doc", "width-scaling-experiment-report.txt");
    private static final int[] MAX_CELLS_PER_ROW_SETTINGS = {16, 50, 100, 200};
    private static final int[] ROW_COUNTS_TO_TRY = {10};
    private static final int ATTEMPTS_PER_ROW_COUNT = 15;
    private static final long BASE_RANDOM_SEED = 202609171L;

    private WidthScalingExperiment() {
    }

    public static void main(String[] args) throws IOException {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);

        StringBuilder report = new StringBuilder();
        report.append("WIDTH-SCALING EXPERIMENT - real circumference achieved at several real maxCellsPerRow settings\n");
        report.append("================================================================================================\n\n");
        report.append("Against: production ModBiomeGraph.OVERWORLD, rowCount fixed at 10 (isolating the horizontal dimension).\n\n");

        for (int maxCellsPerRow : MAX_CELLS_PER_ROW_SETTINGS) {
            int minCellsPerRow = Math.max(6, maxCellsPerRow / 2);
            long seed = BASE_RANDOM_SEED ^ maxCellsPerRow;
            System.out.println("=== maxCellsPerRow=" + maxCellsPerRow + " (minCellsPerRow=" + minCellsPerRow + ") ===");
            long startNanos = System.nanoTime();
            PeriodicCoherenceProof.WitnessSearchResult result = PeriodicCoherenceProof.findWitness(
                table, seed, minCellsPerRow, maxCellsPerRow, ROW_COUNTS_TO_TRY, ATTEMPTS_PER_ROW_COUNT
            );
            double elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

            String line;
            if (result.layout() != null) {
                int cellCount = result.layout().rows().get(0).cells().size();
                line = "maxCellsPerRow=" + maxCellsPerRow + ": SUCCESS - circumferenceBlocks=" + result.layout().circumferenceBlocks()
                    + " (row 0 has " + cellCount + " cells), " + result.totalAttempts() + " attempts, " + String.format("%.1f", elapsedSeconds) + "s";
            } else {
                line = "maxCellsPerRow=" + maxCellsPerRow + ": INCONCLUSIVE after " + result.totalAttempts() + " attempts, "
                    + String.format("%.1f", elapsedSeconds) + "s - not a proof of impossibility, only this bounded search found nothing.";
            }
            System.out.println(line);
            report.append(line).append("\n");
        }

        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report.toString(), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Report: " + REPORT_PATH.toAbsolutePath().toUri());
    }
}
