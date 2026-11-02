package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Same seed sequence for every arm (common random numbers): differences come from the loosening, not seed luck. Arms
 * take turns so their attempt counts stay level. New attempts stop one attempt-length before the deadline and running
 * ones are drained: dropping in-flight attempts would drop mostly failures (they run longest) and flatter every rate.
 */
final class SolveRateTrial {

    static final String BASELINE_LABEL = "graph as is";
    private static final long TRIAL_WORLD_SEED = 0x2F6B_91D4_58A3_C07EL;

    private final PlanSettings settings;
    private final int threads;
    private final Duration wallTime;
    private final double boundConfidence;

    /** {@code boundConfidence}: confidence of EACH one-sided bound (0.975 each = a two-sided 95% range). */
    SolveRateTrial(PlanSettings settings, int threads, Duration wallTime, double boundConfidence) {
        if (threads < 1 || !wallTime.isPositive() || !(boundConfidence > 0.0 && boundConfidence < 1.0)) {
            throw new IllegalArgumentException("SolveRateTrial: need threads >= 1, positive wall time, confidence in (0,1); got " + threads + ", " + wallTime + ", " + boundConfidence);
        }
        this.settings = settings;
        this.threads = threads;
        this.wallTime = wallTime;
        this.boundConfidence = boundConfidence;
    }

    /** First result = the graph as is; then one per arm, same order. The graph as is must be one the generator accepts. */
    List<ArmResult> run(BiomeGraphData baseline, List<LoosenedGraph> arms, WorldBiomePlan.ProgressListener progress) {
        long deadlineNanos = System.nanoTime() + wallTime.toNanos();
        Arm baselineArm = Arm.of(BASELINE_LABEL, baseline, settings);
        baselineArm.rejection().ifPresent(rejection -> {
            throw new IllegalArgumentException("SolveRateTrial: the graph as is is rejected by the generator, nothing to compare against: " + rejection);
        });
        List<Arm> all = new ArrayList<>();
        all.add(baselineArm);
        arms.forEach(arm -> all.add(Arm.of(arm.label(), arm.catalog(), settings)));
        try (Run run = new Run(all, deadlineNanos)) {
            run.execute(progress);
        }
        return resultsOf(baselineArm, all);
    }

    /** IMPROVES: arm's lower bound above the baseline's upper bound - both must miss for this to be luck. */
    static boolean improves(double armLow, double baselineHigh) {
        return armLow > baselineHigh;
    }

    private List<ArmResult> resultsOf(Arm baseline, List<Arm> arms) {
        StageEvidence baselineEvidence = baseline.evidence();
        double baselineHigh = BinomialConfidence.upperBound(baselineEvidence.successes(), baselineEvidence.trials(), boundConfidence);
        return arms.stream().map(arm -> resultOf(arm, arm != baseline, baselineHigh)).toList();
    }

    private ArmResult resultOf(Arm arm, boolean comparedToBaseline, double baselineHigh) {
        if (arm.rejection().isPresent()) {
            return new ArmResult(arm.label(), Optional.empty(), arm.rejection(), false);
        }
        double low = BinomialConfidence.lowerBound(arm.evidence().successes(), arm.evidence().trials(), boundConfidence);
        return new ArmResult(arm.label(), Optional.of(arm.evidence()), Optional.empty(), comparedToBaseline && improves(low, baselineHigh));
    }

    /** One graph under trial; mutable counts touched only by the caller thread. */
    private static final class Arm {
        private final String label;
        private final Optional<SolveStages> stages;
        private final Optional<String> rejection;
        private StageEvidence evidence = StageEvidence.EMPTY;
        private int submitted;

        private Arm(String label, Optional<SolveStages> stages, Optional<String> rejection) {
            this.label = label;
            this.stages = stages;
            this.rejection = rejection;
        }

        static Arm of(String label, BiomeGraphData catalog, PlanSettings settings) {
            try {
                return new Arm(label, Optional.of(SolveStages.of(catalog, settings)), Optional.empty());
            } catch (CatalogRejected rejected) {
                return new Arm(label, Optional.empty(), Optional.of(rejected.getMessage()));
            }
        }

        String label() {
            return label;
        }

        Optional<SolveStages> stages() {
            return stages;
        }

        Optional<String> rejection() {
            return rejection;
        }

        StageEvidence evidence() {
            return evidence;
        }
    }

    private record AttemptOutcome(Arm arm, boolean solved, int cost, long durationNanos) {
    }

    private final class Run implements AutoCloseable {
        private final List<Arm> runnable;
        private final long deadlineNanos;
        private final long baseSeed = ToroidalSizedPatternGenerator.baseSeedOf(TRIAL_WORLD_SEED);
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final IndependentAttemptPool<AttemptOutcome> pool = new IndependentAttemptPool<>(threads);
        private long longestAttemptNanos;
        private int nextArmToTry;

        Run(List<Arm> arms, long deadlineNanos) {
            this.runnable = arms.stream().filter(arm -> arm.stages().isPresent()).toList();
            this.deadlineNanos = deadlineNanos;
        }

        void execute(WorldBiomePlan.ProgressListener progress) {
            progress.report("verify run: " + runnable.size() + " graphs, " + threads + " threads, up to " + wallTime.toSeconds() + " s");
            for (int thread = 0; thread < threads; thread++) {
                submitNext();
            }
            boolean waiting = true;
            while (waiting && pool.running() > 0) {
                waiting = awaitOne(progress);
            }
        }

        /** After the deadline, waits at most one more attempt-length for the last ones; false = gave up, the rest are dropped. */
        private boolean awaitOne(WorldBiomePlan.ProgressListener progress) {
            long waitNanos = Math.max(0, deadlineNanos + longestAttemptNanos - System.nanoTime());
            Optional<AttemptOutcome> finished = pool.takeCompleted(Duration.ofNanos(waitNanos));
            if (finished.isEmpty()) {
                return false;
            }
            record(finished.get(), progress);
            if (startingAnotherFitsBeforeDeadline()) {
                submitNext();
            }
            return true;
        }

        private boolean startingAnotherFitsBeforeDeadline() {
            return System.nanoTime() + longestAttemptNanos < deadlineNanos;
        }

        private void record(AttemptOutcome outcome, WorldBiomePlan.ProgressListener progress) {
            Arm arm = outcome.arm();
            arm.evidence = outcome.solved() ? arm.evidence.withSuccess() : arm.evidence.withFailure(OptionalInt.of(outcome.cost()));
            longestAttemptNanos = Math.max(longestAttemptNanos, outcome.durationNanos());
            progress.report(arm.label() + ": solved " + arm.evidence.describe());
        }

        private void submitNext() {
            Arm arm = leastSubmitted();
            long seed = baseSeed + arm.submitted++;
            pool.submit(() -> attempt(arm, seed));
        }

        /** Fewest attempts so far; ties go round-robin, so no arm always gets a partial round's extra attempt. */
        private Arm leastSubmitted() {
            Arm chosen = null;
            for (int offset = 0; offset < runnable.size(); offset++) {
                Arm candidate = runnable.get((nextArmToTry + offset) % runnable.size());
                if (chosen == null || candidate.submitted < chosen.submitted) {
                    chosen = candidate;
                }
            }
            nextArmToTry = (runnable.indexOf(chosen) + 1) % runnable.size();
            return chosen;
        }

        private AttemptOutcome attempt(Arm arm, long seed) {
            long start = System.nanoTime();
            SolveStages stages = arm.stages().orElseThrow();
            ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(
                stages.table(), stages.coarseGrid(), stages.coarseModel(), seed, settings.patternStepsPerAttempt(), settings.patternSchedule(), stopRequested::get
            );
            boolean solved = result.assignment() != null;
            return new AttemptOutcome(arm, solved, solved ? 0 : result.bestCost(), System.nanoTime() - start);
        }

        @Override
        public void close() {
            stopRequested.set(true);
            pool.close();
        }
    }
}
