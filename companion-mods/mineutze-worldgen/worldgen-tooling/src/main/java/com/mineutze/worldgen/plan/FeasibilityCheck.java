package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Graph editor's verdict, flow in {@code doc/Modelisation/feasibility_check.puml}. Proof covers the coarse stage only:
 * a graph impossible only at the fine stage ends UNPROVEN.
 */
public final class FeasibilityCheck {

    private FeasibilityCheck() {
    }

    public static FeasibilityVerdict check(BiomeGraphData catalog, FeasibilityBudget budget, WorldBiomePlan.ProgressListener progress) {
        return check(catalog, PlanSettings.DEFAULT, budget, progress);
    }

    static FeasibilityVerdict check(BiomeGraphData catalog, PlanSettings settings, FeasibilityBudget budget, WorldBiomePlan.ProgressListener progress) {
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + budget.wallTime().toNanos();
        SolveStages stages;
        try {
            stages = SolveStages.of(catalog, settings);
        } catch (CatalogRejected rejection) {
            return FeasibilityVerdict.infeasible(InfeasibilityProof.rejectedByGenerator(rejection.getMessage()), Evidence.EMPTY, budget.maxWorldFailureProbability(), elapsedSince(startNanos));
        }
        Optional<InfeasibilityProof> proof = proveImpossible(stages, settings, deadlineNanos, progress);
        if (proof.isPresent()) {
            return FeasibilityVerdict.infeasible(proof.get(), Evidence.EMPTY, budget.maxWorldFailureProbability(), elapsedSince(startNanos));
        }
        WorldSearch.Outcome outcome;
        try (WorldSearch search = new WorldSearch(stages, settings, budget.threads(), progress, startNanos, deadlineNanos)) {
            outcome = search.run();
        }
        if (outcome.builtWorld().isEmpty()) {
            return FeasibilityVerdict.unproven(outcome.evidence(), outcome.tightness(), budget.maxWorldFailureProbability(), elapsedSince(startNanos));
        }
        ReliabilityEstimate reliability = ReliabilityEstimate.of(outcome.evidence(), ProductionFailureModel.fromProduction(settings), budget.confidence());
        return FeasibilityVerdict.feasible(outcome.builtWorld().get(), outcome.evidence(), reliability, budget.maxWorldFailureProbability(), elapsedSince(startNanos));
    }

    /** Present only on a proven contradiction; UNKNOWN (solver time limit) is never a proof. */
    private static Optional<InfeasibilityProof> proveImpossible(SolveStages stages, PlanSettings settings, long deadlineNanos, WorldBiomePlan.ProgressListener progress) {
        List<GraphLaw> laws = GraphLaws.of(stages.table(), stages.coarseModel());
        CountingModel counting = CountingModel.of(stages.table(), settings);
        CountingStatus status = counting.check(new LinkedHashSet<>(laws));
        progress.report("counting model over " + laws.size() + " laws: " + status);
        if (status != CountingStatus.CONTRADICTION) {
            return Optional.empty();
        }
        Duration explainTime = Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
        return Optional.of(InfeasibilityProof.of(new ConflictExplainer(counting, explainTime).explain(laws)));
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
