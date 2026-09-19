package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Real reliability tuning sweep of {@link ToroidalIdentityLocalSearch} against the real catalog:
 * full 66-biome coverage on identical seeds for several restart thresholds, reporting the STEP
 * distribution (machine-independent, unlike seconds) and restart counts. A world seed must never
 * hit a slow or failing case, so the far tail matters more than the average. Every success has
 * already been re-verified against {@link RawRuleTable#isLegal} on every torus edge inside the search.
 */
public final class ToroidalIdentityLocalSearchRunner {

    private static final int GRID_SIZE = 24;
    private static final int SEEDS = 400;
    private static final long MAX_MILLIS_PER_RUN = 60_000;
    private static final long[] STALL_STEPS_TO_TRY = {100_000, 250_000, 500_000, 1_000_000};
    private static final long RANDOM_SEED_BASE = 9_000_001L;

    private ToroidalIdentityLocalSearchRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData data = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        RawRuleTable table = RawRuleTable.from(data);
        ToroidalGrid grid = new ToroidalGrid(GRID_SIZE, GRID_SIZE);

        for (long stallSteps : STALL_STEPS_TO_TRY) {
            List<Long> stepCounts = new ArrayList<>();
            List<Double> secondsList = new ArrayList<>();
            int restartedRuns = 0;
            int failed = 0;
            for (int seedIndex = 0; seedIndex < SEEDS; seedIndex++) {
                long startNanos = System.nanoTime();
                ToroidalIdentityLocalSearch.Result result = ToroidalIdentityLocalSearch.search(table, grid, RANDOM_SEED_BASE + seedIndex, MAX_MILLIS_PER_RUN, stallSteps);
                double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                if (result.assignment() == null) {
                    failed++;
                    continue;
                }
                stepCounts.add(result.steps());
                secondsList.add(seconds);
                if (result.restarts() > 0) {
                    restartedRuns++;
                }
            }
            Collections.sort(stepCounts);
            Collections.sort(secondsList);
            System.out.println(
                "stall=" + stallSteps + " " + GRID_SIZE + "x" + GRID_SIZE + ": solved " + stepCounts.size() + "/" + SEEDS + " (" + failed + " failed), "
                    + restartedRuns + " runs needed a restart. steps: median " + at(stepCounts, 0.5) + " p90 " + at(stepCounts, 0.9) + " p99 " + at(stepCounts, 0.99)
                    + " max " + stepCounts.get(stepCounts.size() - 1) + String.format(". seconds: median %.2f p99 %.2f max %.2f", at(secondsList, 0.5), at(secondsList, 0.99), secondsList.get(secondsList.size() - 1))
            );
        }
    }

    private static <T> T at(List<T> sorted, double fraction) {
        return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * fraction)));
    }
}
