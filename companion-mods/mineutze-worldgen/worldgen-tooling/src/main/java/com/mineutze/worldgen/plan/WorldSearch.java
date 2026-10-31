package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-identical coarse / refinement / render attempts on every thread until a full world is built or the
 * deadline passes. Attempts use production's own steps, schedule, models - no reduced-budget shortcut. Calling thread
 * owns all mutable state; pool threads only compute attempts.
 */
final class WorldSearch implements AutoCloseable {

    record Outcome(Optional<BuiltWorld> builtWorld, Evidence evidence, StageTightness tightness) {
    }

    /** Obstacles computed on the worker thread, so the caller only merges counts and refills threads at once. */
    private record FinishedAttempt(AttemptResult result, Set<Obstacle> obstacles) {
    }

    private static final long SEARCH_WORLD_SEED = 0x6A3F_19C2_D04E_7B85L;
    private static final int PROGRESS_EVERY_ATTEMPTS = 25;
    /** One render runs at a time and the first success ends the search; patterns beyond this would never be rendered. */
    private static final int MOST_PATTERNS_AWAITING_RENDER = 4;
    private static final double NANOS_PER_SECOND = 1e9;

    private final SolveStages stages;
    private final PlanSettings settings;
    private final int threads;
    private final WorldBiomePlan.ProgressListener progress;
    private final long startNanos;
    private final long deadlineNanos;
    private final IndependentAttemptPool<FinishedAttempt> pool;
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final Deque<Map<ToroidalPos, BiomeId>> fineSeedsAwaitingRefinement = new ArrayDeque<>();
    private final Deque<Map<ToroidalPos, BiomeId>> patternsAwaitingRender = new ArrayDeque<>();
    private final long coarseBaseSeed = ToroidalSizedPatternGenerator.baseSeedOf(SEARCH_WORLD_SEED);
    private final long refinementBaseSeed = new SplittableRandom(SEARCH_WORLD_SEED).nextLong();
    private final int refinementAttemptsPerPattern;

    private Evidence evidence = Evidence.EMPTY;
    private long nextCoarseIndex;
    private long nextRefinementIndex;
    private long nextRenderIndex;
    private boolean renderInFlight;
    private Optional<BuiltWorld> builtWorld = Optional.empty();
    private final TightnessTally coarseTightness = new TightnessTally();
    private final TightnessTally refinementTightness = new TightnessTally();

    WorldSearch(SolveStages stages, PlanSettings settings, int threads, WorldBiomePlan.ProgressListener progress, long startNanos, long deadlineNanos) {
        this.stages = stages;
        this.settings = settings;
        this.threads = threads;
        this.progress = progress;
        this.startNanos = startNanos;
        this.deadlineNanos = deadlineNanos;
        this.refinementAttemptsPerPattern = ProductionFailureModel.fromProduction(settings).refinementAttemptsPerWorld();
        if (stages.fineGrid().isPresent() && refinementAttemptsPerPattern < 1) {
            throw new IllegalArgumentException("WorldSearch: fine stage configured with " + refinementAttemptsPerPattern + " refinement attempts; a solved coarse pattern could never be refined");
        }
        this.pool = new IndependentAttemptPool<>(threads);
    }

    Outcome run() {
        progress.report("searching for a world: " + stages.table().allBiomeIds().size() + " biomes, " + threads + " threads");
        for (int thread = 0; thread < threads; thread++) {
            submitNextWork();
        }
        boolean searchOver = false;
        while (!searchOver) {
            searchOver = step();
        }
        stopRequested.set(true);
        return new Outcome(builtWorld, evidence, new StageTightness(coarseTightness.ranking(), refinementTightness.ranking()));
    }

    /** Waits for one attempt or the deadline, records it, refills its thread. True once a world is built or time is up. */
    private boolean step() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            recordAlreadyFinished();
            return true;
        }
        Optional<FinishedAttempt> finished = pool.takeCompleted(Duration.ofNanos(remainingNanos));
        if (finished.isEmpty()) {
            return false;
        }
        record(finished.get());
        if (builtWorld.isPresent()) {
            return true;
        }
        submitNextWork();
        return false;
    }

    /** At the deadline: attempts that finished in time still count - one of them may be the world. */
    private void recordAlreadyFinished() {
        Optional<FinishedAttempt> finished = pool.running() > 0 ? pool.takeCompleted(Duration.ZERO) : Optional.empty();
        while (finished.isPresent() && builtWorld.isEmpty()) {
            record(finished.get());
            finished = pool.running() > 0 ? pool.takeCompleted(Duration.ZERO) : Optional.empty();
        }
    }

    private void record(FinishedAttempt finished) {
        AttemptResult result = finished.result();
        evidence = evidence.with(result);
        progress.report(String.format(
            Locale.ROOT, "%s attempt %s in %.1f s (at %d s)", result.stage(), result.solved() ? "solved" : "failed", result.duration().toNanos() / NANOS_PER_SECOND,
            elapsed().toSeconds()
        ));
        switch (result.stage()) {
            case COARSE -> recordCoarse(result, finished.obstacles());
            case REFINEMENT -> recordRefinement(result, finished.obstacles());
            case REALIZATION -> recordRender(result);
        }
        reportProgressPeriodically();
    }

    private void recordCoarse(AttemptResult result, Set<Obstacle> obstacles) {
        if (!result.solved()) {
            coarseTightness.add(obstacles);
            return;
        }
        Map<ToroidalPos, BiomeId> pattern = result.solvedPattern().orElseThrow();
        if (stages.fineGrid().isEmpty()) {
            queueForRender(pattern);
            return;
        }
        Map<ToroidalPos, BiomeId> fineSeed = MultigridPatternGenerator.fineSeedOf(pattern, settings);
        for (int attempt = 0; attempt < refinementAttemptsPerPattern; attempt++) {
            fineSeedsAwaitingRefinement.add(fineSeed);
        }
    }

    private void recordRefinement(AttemptResult result, Set<Obstacle> obstacles) {
        if (result.solved()) {
            queueForRender(result.solvedPattern().orElseThrow());
            return;
        }
        refinementTightness.add(obstacles);
    }

    private void queueForRender(Map<ToroidalPos, BiomeId> pattern) {
        if (patternsAwaitingRender.size() < MOST_PATTERNS_AWAITING_RENDER) {
            patternsAwaitingRender.add(pattern);
        }
    }

    private void recordRender(AttemptResult result) {
        renderInFlight = false;
        if (result.solved()) {
            builtWorld = Optional.of(new BuiltWorld(result.solvedPattern().orElseThrow(), result.renderedMap().orElseThrow(), evidence.totalTrials()));
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

    /** Max 1 render in flight: parallel rasterizer starves search threads (DECISIONS.md 2026-10-31). Render first - it can finish the search. */
    private void submitNextWork() {
        if (!renderInFlight && !patternsAwaitingRender.isEmpty()) {
            submitRender(patternsAwaitingRender.removeFirst());
        } else if (!fineSeedsAwaitingRefinement.isEmpty()) {
            submitRefinement(fineSeedsAwaitingRefinement.removeFirst());
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
        long worldSeed = SEARCH_WORLD_SEED + nextRenderIndex++;
        pool.submit(() -> runRenderAttempt(pattern, worldSeed));
    }

    private FinishedAttempt runCoarseAttempt(long seed) {
        long start = System.nanoTime();
        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(
            stages.table(), stages.coarseGrid(), stages.coarseModel(), seed, settings.patternStepsPerAttempt(), settings.patternSchedule(), stopRequested::get
        );
        AttemptResult attempt = AttemptResult.ofSearch(AttemptResult.Stage.COARSE, result, Duration.ofNanos(System.nanoTime() - start));
        return new FinishedAttempt(attempt, obstaclesOf(attempt, stages.coarseGrid(), stages.coarseModel()));
    }

    private FinishedAttempt runRefinementAttempt(Map<ToroidalPos, BiomeId> fineSeed, long seed) {
        long start = System.nanoTime();
        PlanSettings.FineResolution fine = settings.fineResolution();
        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.searchFromSeed(
            stages.table(), stages.fineGrid().orElseThrow(), stages.fineModel().orElseThrow(), seed, fineSeed, fine.refinementStepsPerAttempt(), fine.refinementSchedule(),
            stopRequested::get, ToroidalSizedLocalSearch.DEFAULT_RELABEL_PROBABILITY
        );
        AttemptResult attempt = AttemptResult.ofSearch(AttemptResult.Stage.REFINEMENT, result, Duration.ofNanos(System.nanoTime() - start));
        return new FinishedAttempt(attempt, obstaclesOf(attempt, stages.fineGrid().orElseThrow(), stages.fineModel().orElseThrow()));
    }

    private Set<Obstacle> obstaclesOf(AttemptResult attempt, ToroidalGrid grid, ClusterSizeModel model) {
        return attempt.closestState().map(closest -> ObstacleFinder.of(stages.table(), grid, model, closest)).orElse(Set.of());
    }

    private FinishedAttempt runRenderAttempt(Map<ToroidalPos, BiomeId> pattern, long worldSeed) {
        long start = System.nanoTime();
        StringBuilder whyStrengthsFailed = new StringBuilder();
        Optional<RealizationSelector.Selection> selection = RealizationSelector.trySelect(
            stages.table(), stages.renderGrid(), pattern, stages.renderGeometry(), worldSeed, settings, whyStrengthsFailed
        );
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        AttemptResult attempt = selection.map(chosen -> AttemptResult.rendered(pattern, chosen.map(), duration))
            .orElseGet(() -> AttemptResult.renderFailed("world seed " + worldSeed + ":" + whyStrengthsFailed, duration));
        return new FinishedAttempt(attempt, Set.of());
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
