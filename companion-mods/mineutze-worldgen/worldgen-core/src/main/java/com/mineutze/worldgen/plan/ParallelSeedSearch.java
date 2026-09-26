package com.mineutze.worldgen.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@link ToroidalSizedLocalSearch} from many independent seeds at once and keeps the first
 * to solve (2026-09-19). One seed solves the real catalog only some of the time - the walk can
 * freeze a few illegal edges short of a solution - so independent restarts on separate threads
 * multiply the throughput; nothing else about a single run changes.
 *
 * <p><b>Deterministic on purpose</b>: the answer is "the LOWEST seed index that solves within its
 * own step budget", never "whichever thread happened to finish first", so a world seed always
 * yields the same pattern whatever the thread count or scheduling. A run is stopped early only
 * when a LOWER index has already solved - such a run can no longer be the winner - so a run that
 * could still be the winner is never cut short. If no seed solves, the lowest-cost state (lowest
 * index on ties) is reported.
 */
final class ParallelSeedSearch {

    private ParallelSeedSearch() {
    }

    /** The winning seed's result, or - when nothing solved - the closest result of any seed. */
    record Outcome(long seed, ToroidalSizedLocalSearch.Result result) {
        boolean solved() {
            return result.assignment() != null;
        }
    }

    static Outcome search(
        RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long baseSeed, int seedCount, long stepsPerSeed,
        ToroidalSizedLocalSearch.Schedule schedule, int threadCount
    ) {
        if (seedCount <= 0 || stepsPerSeed <= 0 || threadCount <= 0) {
            throw new IllegalArgumentException(
                "ParallelSeedSearch: seedCount, stepsPerSeed and threadCount must be positive, got " + seedCount + ", " + stepsPerSeed + ", " + threadCount
            );
        }
        AtomicInteger lowestSolvedIndex = new AtomicInteger(Integer.MAX_VALUE);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threadCount, seedCount));
        try {
            List<Future<ToroidalSizedLocalSearch.Result>> runs = new ArrayList<>();
            for (int index = 0; index < seedCount; index++) {
                int seedIndex = index;
                runs.add(pool.submit(() -> runSeed(table, grid, model, baseSeed + seedIndex, seedIndex, stepsPerSeed, schedule, lowestSolvedIndex)));
            }
            return pickOutcome(baseSeed, runs);
        } finally {
            pool.shutdownNow();
        }
    }

    private static ToroidalSizedLocalSearch.Result runSeed(
        RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long seed, int seedIndex, long stepsPerSeed,
        ToroidalSizedLocalSearch.Schedule schedule, AtomicInteger lowestSolvedIndex
    ) {
        if (lowestSolvedIndex.get() < seedIndex) {
            return null;
        }
        ToroidalSizedLocalSearch.Result result = ToroidalSizedLocalSearch.search(
            table, grid, model, seed, stepsPerSeed, schedule, () -> lowestSolvedIndex.get() < seedIndex
        );
        if (result.assignment() != null) {
            lowestSolvedIndex.accumulateAndGet(seedIndex, Math::min);
        }
        return result;
    }

    private static Outcome pickOutcome(long baseSeed, List<Future<ToroidalSizedLocalSearch.Result>> runs) {
        Outcome closest = null;
        for (int index = 0; index < runs.size(); index++) {
            closest = processRunResult(baseSeed, runs, index, closest);
            if (closest != null && closest.solved()) {
                return closest;
            }
        }
        if (closest == null) {
            throw new IllegalStateException("ParallelSeedSearch: every seed was skipped but none solved - impossible unless a seed was lost");
        }
        return closest;
    }

    private static Outcome processRunResult(long baseSeed, List<Future<ToroidalSizedLocalSearch.Result>> runs, int index, Outcome closest) {
        ToroidalSizedLocalSearch.Result result = resultOf(baseSeed + index, runs.get(index));
        if (result == null) {
            return closest;
        }
        if (result.assignment() != null) {
            return new Outcome(baseSeed + index, result);
        }
        if (closest == null || result.bestCost() < closest.result().bestCost()) {
            return new Outcome(baseSeed + index, result);
        }
        return closest;
    }

    private static ToroidalSizedLocalSearch.Result resultOf(long seed, Future<ToroidalSizedLocalSearch.Result> run) {
        try {
            return run.get();
        } catch (ExecutionException failure) {
            throw new IllegalStateException("ParallelSeedSearch: seed " + seed + " failed: " + failure.getCause(), failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ParallelSeedSearch: interrupted while waiting for seed " + seed, interrupted);
        }
    }
}
