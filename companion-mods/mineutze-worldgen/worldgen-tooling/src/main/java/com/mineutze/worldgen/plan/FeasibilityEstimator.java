package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Feasibility verdict in time budget: estimates per-stage success rate, maps to world-seed failure odds, stops when
 * {@link SequentialVerdictRule} decides. Flow: {@code doc/Modelisation/feasibility_estimate.puml}.
 *
 * <p>Attempts use production's own steps, schedule, models - measured rate IS production's rate, no reduced-budget
 * shortcut. Refinement rate pooled across coarse patterns: approximation, patterns differ.
 */
public final class FeasibilityEstimator {

    private static final long ESTIMATE_WORLD_SEED = 0x6A3F_19C2_D04E_7B85L;
    private static final int PROGRESS_EVERY_ATTEMPTS = 25;
    private static final double NANOS_PER_SECOND = 1e9;

    private FeasibilityEstimator() {
    }

    public static FeasibilityVerdict estimate(BiomeGraphData catalog, FeasibilityBudget budget, WorldBiomePlan.ProgressListener progress) {
        return estimate(catalog, PlanSettings.DEFAULT, budget, progress);
    }

    static FeasibilityVerdict estimate(BiomeGraphData catalog, PlanSettings settings, FeasibilityBudget budget, WorldBiomePlan.ProgressListener progress) {
        long startNanos = System.nanoTime();
        Stages stages;
        try {
            stages = Stages.of(catalog, settings);
        } catch (CatalogRejected rejection) {
            return FeasibilityVerdict.rejected(rejection.getMessage(), Duration.ofNanos(System.nanoTime() - startNanos));
        }
        try (EstimationRun run = new EstimationRun(stages, settings, budget, progress, startNanos)) {
            return run.execute();
        }
    }

    /** Graph itself rejected by generator - as opposed to a bug, which must propagate. */
    private static final class CatalogRejected extends RuntimeException {
        CatalogRejected(String message) {
            super(message);
        }
    }

    /** Same grids/models as production, so rejections match world creation. */
    private record Stages(
        RawRuleTable table, ToroidalGrid coarseGrid, ClusterSizeModel coarseModel, Optional<ToroidalGrid> fineGrid, Optional<ClusterSizeModel> fineModel,
        ToroidalGrid renderGrid, HexTorusGeometry renderGeometry
    ) {

        static Stages of(BiomeGraphData catalog, PlanSettings settings) {
            ToroidalGrid coarseGrid = new ToroidalGrid(settings.gridSize(), settings.gridSize());
            RawRuleTable table = readCatalog(() -> RawRuleTable.from(catalog));
            ClusterSizeModel coarseModel = readCatalog(() -> MultigridPatternGenerator.coarseModelOf(table, settings));
            requireRoomForEveryBiome(table, coarseGrid, coarseModel);
            PlanSettings.FineResolution fine = settings.fineResolution();
            if (fine == null) {
                HexTorusGeometry geometry = new HexTorusGeometry(settings.gridSize(), settings.gridSize(), settings.atomWidthBlocks());
                return new Stages(table, coarseGrid, coarseModel, Optional.empty(), Optional.empty(), coarseGrid, geometry);
            }
            ToroidalGrid fineGrid = new ToroidalGrid(fine.gridSize(), fine.gridSize());
            ClusterSizeModel fineModel = readCatalog(() -> MultigridPatternGenerator.fineModelOf(table, settings));
            requireRoomForEveryBiome(table, fineGrid, fineModel);
            HexTorusGeometry geometry = new HexTorusGeometry(fine.gridSize(), fine.gridSize(), fine.atomWidthBlocks());
            return new Stages(table, coarseGrid, coarseModel, Optional.of(fineGrid), Optional.of(fineModel), fineGrid, geometry);
        }

        /** Catalog readers signal a bad graph with IllegalArgumentException; only those calls are wrapped. */
        private static <T> T readCatalog(Supplier<T> reader) {
            try {
                return reader.get();
            } catch (IllegalArgumentException badGraph) {
                throw new CatalogRejected(badGraph.getMessage());
            }
        }

        private static void requireRoomForEveryBiome(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model) {
            long minimumCellsNeeded = ToroidalSizedPatternGenerator.minimumCellsOfAllBiomes(table, model);
            if (minimumCellsNeeded > grid.cellCount()) {
                throw new CatalogRejected(
                    "each biome needs at least one cluster of its minimum size, which add up to " + minimumCellsNeeded + " cells, but the " + grid.width() + "x"
                        + grid.height() + " torus has " + grid.cellCount()
                );
            }
        }
    }

    /** Calling thread owns all mutable state; pool threads only compute attempts. */
    private static final class EstimationRun implements AutoCloseable {

        private final Stages stages;
        private final PlanSettings settings;
        private final FeasibilityBudget budget;
        private final WorldBiomePlan.ProgressListener progress;
        private final long startNanos;
        private final long deadlineNanos;
        private final SequentialVerdictRule rule;
        private final IndependentAttemptPool<AttemptResult> pool;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final Deque<Map<ToroidalPos, BiomeId>> fineSeedsAwaitingRefinement = new ArrayDeque<>();
        private final Deque<Map<ToroidalPos, BiomeId>> patternsAwaitingRender = new ArrayDeque<>();
        private final long coarseBaseSeed = ToroidalSizedPatternGenerator.baseSeedOf(ESTIMATE_WORLD_SEED);
        private final long refinementBaseSeed = new SplittableRandom(ESTIMATE_WORLD_SEED).nextLong();
        private final int refinementAttemptsPerPattern;

        private Evidence evidence = Evidence.EMPTY;
        private long nextCoarseIndex;
        private long nextRefinementIndex;
        private long nextRenderIndex;
        private boolean renderInFlight;
        private Optional<AttemptResult> closestCoarseFailure = Optional.empty();
        private Optional<String> firstRenderFailure = Optional.empty();

        EstimationRun(Stages stages, PlanSettings settings, FeasibilityBudget budget, WorldBiomePlan.ProgressListener progress, long startNanos) {
            this.stages = stages;
            this.settings = settings;
            this.budget = budget;
            this.progress = progress;
            this.startNanos = startNanos;
            this.deadlineNanos = startNanos + budget.wallTime().toNanos();
            ProductionFailureModel model = ProductionFailureModel.fromProduction(settings);
            this.rule = new SequentialVerdictRule(model, budget);
            this.refinementAttemptsPerPattern = model.refinementAttemptsPerWorld();
            this.pool = new IndependentAttemptPool<>(budget.threads());
        }

        FeasibilityVerdict execute() {
            progress.report("estimating feasibility: " + stages.table().allBiomeIds().size() + " biomes, " + budget.threads() + " threads, up to " + budget.wallTime().toSeconds() + " s");
            for (int thread = 0; thread < budget.threads(); thread++) {
                submitNextWork();
            }
            Optional<FeasibilityVerdict.Outcome> outcome = Optional.empty();
            while (outcome.isEmpty()) {
                outcome = step();
            }
            stopRequested.set(true);
            return verdictOf(outcome.get());
        }

        /** Waits for one attempt or the deadline, records it, refills its thread. Returns the outcome once decided. */
        private Optional<FeasibilityVerdict.Outcome> step() {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return Optional.of(rule.decide(evidence, SequentialVerdictRule.Deadline.REACHED)
                    .orElseThrow(() -> new IllegalStateException("FeasibilityEstimator: deadline reached but no verdict for " + evidence)));
            }
            Optional<AttemptResult> finished = pool.takeCompleted(Duration.ofNanos(remainingNanos));
            if (finished.isEmpty()) {
                return Optional.empty();
            }
            record(finished.get());
            Optional<FeasibilityVerdict.Outcome> outcome = rule.decide(evidence, SequentialVerdictRule.Deadline.NOT_REACHED);
            if (outcome.isEmpty()) {
                submitNextWork();
            }
            return outcome;
        }

        private void record(AttemptResult result) {
            evidence = evidence.with(result);
            progress.report(String.format(
                Locale.ROOT, "%s attempt %s in %.1f s (at %d s)", result.stage(), result.solved() ? "solved" : "failed", result.duration().toNanos() / NANOS_PER_SECOND,
                elapsed().toSeconds()
            ));
            switch (result.stage()) {
                case COARSE -> recordCoarse(result);
                case REFINEMENT -> recordRefinement(result);
                case REALIZATION -> recordRender(result);
            }
            reportProgressPeriodically();
        }

        private void recordCoarse(AttemptResult result) {
            if (!result.solved()) {
                keepIfClosest(result);
                return;
            }
            Map<ToroidalPos, BiomeId> pattern = result.solvedPattern().orElseThrow();
            if (stages.fineGrid().isEmpty()) {
                patternsAwaitingRender.add(pattern);
                return;
            }
            Map<ToroidalPos, BiomeId> fineSeed = MultigridPatternGenerator.fineSeedOf(pattern, settings);
            for (int attempt = 0; attempt < refinementAttemptsPerPattern; attempt++) {
                fineSeedsAwaitingRefinement.add(fineSeed);
            }
        }

        private void recordRefinement(AttemptResult result) {
            result.solvedPattern().ifPresent(patternsAwaitingRender::add);
        }

        private void recordRender(AttemptResult result) {
            renderInFlight = false;
            if (firstRenderFailure.isEmpty()) {
                firstRenderFailure = result.renderFailureReason();
            }
        }

        private void keepIfClosest(AttemptResult result) {
            int cost = result.failedCost().orElseThrow();
            boolean closer = closestCoarseFailure.map(closest -> cost < closest.failedCost().orElseThrow()).orElse(true);
            if (closer) {
                closestCoarseFailure = Optional.of(result);
            }
        }

        private void reportProgressPeriodically() {
            if (evidence.totalTrials() % PROGRESS_EVERY_ATTEMPTS == 0) {
                progress.report(
                    "after " + elapsed().toSeconds() + " s: coarse " + evidence.coarse().describe() + ", refinement " + evidence.refinement().describe() + ", render "
                        + evidence.realization().describe()
                );
            }
        }

        /**
         * Max 1 render in flight: parallel rasterizer starves search threads (DECISIONS.md 2026-10-31). First clean render
         * jumps queue (PASS needs 1); later renders only after refinement work.
         */
        private void submitNextWork() {
            boolean renderSlotFree = !renderInFlight && !patternsAwaitingRender.isEmpty();
            if (renderSlotFree && evidence.realization().successes() == 0) {
                submitRender(patternsAwaitingRender.removeFirst());
            } else if (!fineSeedsAwaitingRefinement.isEmpty()) {
                submitRefinement(fineSeedsAwaitingRefinement.removeFirst());
            } else if (renderSlotFree) {
                submitRender(patternsAwaitingRender.removeFirst());
            } else {
                submitCoarse();
            }
        }

        private void submitCoarse() {
            long seed = coarseBaseSeed + nextCoarseIndex++;
            pool.submit(() -> runCoarseAttempt(seed));
        }

        private void submitRefinement(Map<ToroidalPos, BiomeId> fineSeed) {
            long seed = refinementBaseSeed + nextRefinementIndex++;
            pool.submit(() -> runRefinementAttempt(fineSeed, seed));
        }

        private void submitRender(Map<ToroidalPos, BiomeId> pattern) {
            renderInFlight = true;
            long worldSeed = ESTIMATE_WORLD_SEED + nextRenderIndex++;
            pool.submit(() -> runRenderAttempt(pattern, worldSeed));
        }

        private AttemptResult runCoarseAttempt(long seed) {
            long start = System.nanoTime();
            ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(
                stages.table(), stages.coarseGrid(), stages.coarseModel(), seed, settings.patternStepsPerAttempt(), settings.patternSchedule(), stopRequested::get
            );
            return AttemptResult.ofSearch(AttemptResult.Stage.COARSE, result, Duration.ofNanos(System.nanoTime() - start));
        }

        private AttemptResult runRefinementAttempt(Map<ToroidalPos, BiomeId> fineSeed, long seed) {
            long start = System.nanoTime();
            PlanSettings.FineResolution fine = settings.fineResolution();
            ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.searchFromSeed(
                stages.table(), stages.fineGrid().orElseThrow(), stages.fineModel().orElseThrow(), seed, fineSeed, fine.refinementStepsPerAttempt(),
                fine.refinementSchedule(), stopRequested::get, ToroidalSizedLocalSearch.DEFAULT_RELABEL_PROBABILITY
            );
            return AttemptResult.ofSearch(AttemptResult.Stage.REFINEMENT, result, Duration.ofNanos(System.nanoTime() - start));
        }

        private AttemptResult runRenderAttempt(Map<ToroidalPos, BiomeId> pattern, long worldSeed) {
            long start = System.nanoTime();
            StringBuilder whyStrengthsFailed = new StringBuilder();
            boolean rendered = RealizationSelector.trySelect(stages.table(), stages.renderGrid(), pattern, stages.renderGeometry(), worldSeed, settings, whyStrengthsFailed)
                .isPresent();
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            return rendered ? AttemptResult.rendered(duration) : AttemptResult.renderFailed("world seed " + worldSeed + ":" + whyStrengthsFailed, duration);
        }

        private FeasibilityVerdict verdictOf(FeasibilityVerdict.Outcome outcome) {
            return new FeasibilityVerdict(
                outcome, evidence, rule.worldFailureLowerBound(evidence), rule.certifiedWorldFailureUpperBound(evidence), elapsed(), Optional.empty(),
                closestCoarseFailure.map(this::describeCoarseFailure), firstRenderFailure
            );
        }

        private String describeCoarseFailure(AttemptResult failure) {
            Map<ToroidalPos, BiomeId> closest = failure.closestState().orElseThrow();
            ToroidalSizedLocalSearch.Breakdown breakdown = ToroidalSizedLocalSearch.breakdown(stages.table(), stages.coarseGrid(), stages.coarseModel(), closest);
            return "  cost " + failure.failedCost().orElseThrow() + " (illegal edges " + breakdown.illegalEdges() + ", cluster violation " + breakdown.clusterViolation()
                + ", missing biomes " + breakdown.missingBiomes() + ")\nSize slack per biome, most squeezed first:"
                + SizeSlackReport.of(stages.table(), stages.coarseGrid(), stages.coarseModel(), closest) + AdjacencyReport.of(stages.table(), stages.coarseGrid(), closest);
        }

        private Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - startNanos);
        }

        @Override
        public void close() {
            stopRequested.set(true);
            pool.close();
        }
    }
}
