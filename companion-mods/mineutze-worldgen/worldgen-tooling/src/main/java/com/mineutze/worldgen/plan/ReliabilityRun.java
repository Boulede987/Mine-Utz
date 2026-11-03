package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;

import java.time.Duration;

/** The editor's Reliability run: production attempts for a set time, past the first world, to narrow the seed-failure range. */
public final class ReliabilityRun {

    private ReliabilityRun() {
    }

    /** Graph the generator refuses: crashes (CatalogRejected) - the editor only saves graphs that construct. */
    public static ReliabilityReport run(BiomeGraphData catalog, Duration wallTime, int threads, WorldBiomePlan.ProgressListener progress) {
        return run(catalog, PlanSettings.DEFAULT, FeasibilityBudget.EDITOR_DEFAULT, wallTime, threads, progress);
    }

    static ReliabilityReport run(BiomeGraphData catalog, PlanSettings settings, FeasibilityBudget budget, Duration wallTime, int threads, WorldBiomePlan.ProgressListener progress) {
        long startNanos = System.nanoTime();
        SolveStages stages = SolveStages.of(catalog, settings);
        ProductionFailureModel model = ProductionFailureModel.fromProduction(settings);
        double target = budget.maxWorldFailureProbability();
        WorldSearch.Outcome outcome;
        try (WorldSearch search = new WorldSearch(stages, settings, threads, progress, startNanos, startNanos + wallTime.toNanos(), WorldSearch.SearchEnd.DEADLINE,
            evidenceSoFar -> progress.report("so far: " + rangeSoFar(evidenceSoFar, model, budget)))) {
            outcome = search.run();
        }
        ReliabilityEstimate estimate = ReliabilityEstimate.of(outcome.evidence(), model, budget.confidence());
        return new ReliabilityReport(ReliabilityVerdict.of(estimate, target), estimate, outcome.evidence(), outcome.tightness(), target,
            Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static String rangeSoFar(Evidence evidence, ProductionFailureModel model, FeasibilityBudget budget) {
        ReliabilityEstimate estimate = ReliabilityEstimate.of(evidence, model, budget.confidence());
        return ReliabilityReport.rangeLine(estimate, budget.maxWorldFailureProbability(), ReliabilityVerdict.of(estimate, budget.maxWorldFailureProbability()));
    }
}
