package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Every independent minimal conflict among a graph's laws: QuickXplain (Junker 2004) finds one minimal contradicting
 * subset, set aside, repeat while the rest contradicts. Conflicts share no law - fixing each makes progress, no
 * whack-a-mole. UNKNOWN = not proven contradicting; a conflict counts only once verified CONTRADICTION alone.
 *
 * <p>Per-biome pass first, on a model holding only that biome's variables: local mistakes (isolated biome = 65 pair
 * laws in one conflict) are cheap there and too slow among all laws (DECISIONS.md 2026-10-31). Then shrunk on the full
 * model, so minimality holds for the full model too.
 */
final class ConflictExplainer {

    /** {@code complete}: what remains after removing every conflict was verified satisfiable, so no conflict is missing. */
    record Explanation(List<LawConflict> conflicts, boolean complete) {
        Explanation {
            conflicts = List.copyOf(conflicts);
        }
    }

    private final CountingModel model;
    private final Duration timeLimit;

    ConflictExplainer(CountingModel model, Duration timeLimit) {
        this.model = model;
        this.timeLimit = timeLimit;
    }

    Explanation explain(List<GraphLaw> laws) {
        Run run = new Run(System.nanoTime() + timeLimit.toNanos());
        List<GraphLaw> remaining = new ArrayList<>(laws);
        List<LawConflict> conflicts = new ArrayList<>();
        try {
            for (BiomeId biome : biomesOf(laws)) {
                run.localConflict(biome, remaining).ifPresent(conflict -> recordAndSetAside(conflict, conflicts, remaining));
            }
            Optional<LawConflict> next = run.nextConflict(remaining);
            while (next.isPresent()) {
                recordAndSetAside(next.get(), conflicts, remaining);
                next = run.nextConflict(remaining);
            }
            return new Explanation(conflicts, true);
        } catch (Incomplete incomplete) {
            return new Explanation(conflicts, false);
        }
    }

    private static void recordAndSetAside(LawConflict conflict, List<LawConflict> conflicts, List<GraphLaw> remaining) {
        conflicts.add(conflict);
        remaining.removeAll(conflict.laws());
    }

    private static List<BiomeId> biomesOf(List<GraphLaw> laws) {
        return laws.stream().filter(law -> law instanceof SizeRangeLaw).map(law -> ((SizeRangeLaw) law).biome()).distinct().toList();
    }

    private static boolean concerns(GraphLaw law, BiomeId biome) {
        return switch (law) {
            case SizeRangeLaw size -> size.biome().equals(biome);
            case RequiredBorderLaw required -> required.biome().equals(biome);
            case IllegalPairLaw pair -> pair.first().equals(biome) || pair.second().equals(biome);
        };
    }

    private static List<GraphLaw> union(List<GraphLaw> first, List<GraphLaw> second) {
        List<GraphLaw> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    /** One explanation's deadline and checks. */
    private final class Run {

        private final long deadlineNanos;

        Run(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        /** Shrunk on the focused model (cheap), then on the full model (minimality must hold there). */
        Optional<LawConflict> localConflict(BiomeId biome, List<GraphLaw> remaining) {
            List<GraphLaw> local = remaining.stream().filter(law -> concerns(law, biome)).toList();
            Predicate<List<GraphLaw>> contradictsFocused = candidates -> contradicts(candidates, biome);
            if (local.isEmpty() || !contradictsFocused.test(local)) {
                return Optional.empty();
            }
            List<GraphLaw> focusedMinimal = minimalConflict(local, contradictsFocused);
            return Optional.of(new LawConflict(shrinkOnFullModel(focusedMinimal)));
        }

        Optional<LawConflict> nextConflict(List<GraphLaw> candidates) {
            if (!contradicts(candidates)) {
                return Optional.empty();
            }
            return Optional.of(new LawConflict(minimalConflict(candidates, this::contradicts)));
        }

        /** Candidates already known to contradict under {@code contradicting}; QuickXplain must return a non-empty verified subset. */
        private List<GraphLaw> minimalConflict(List<GraphLaw> candidates, Predicate<List<GraphLaw>> contradicting) {
            List<GraphLaw> minimal = quickXplain(List.of(), false, candidates, contradicting);
            if (minimal.isEmpty() || !contradicting.test(minimal)) {
                throw new Incomplete();
            }
            return List.copyOf(minimal);
        }

        /** Focused contradiction implies full contradiction (relaxation); full model may still contradict with fewer laws - drop those. */
        private List<GraphLaw> shrinkOnFullModel(List<GraphLaw> conflict) {
            if (!contradicts(conflict)) {
                throw new Incomplete();
            }
            List<GraphLaw> kept = new ArrayList<>(conflict);
            for (GraphLaw law : conflict) {
                dropIfStillContradicting(kept, law);
            }
            return kept;
        }

        private void dropIfStillContradicting(List<GraphLaw> kept, GraphLaw law) {
            List<GraphLaw> without = new ArrayList<>(kept);
            without.remove(law);
            if (contradicts(without)) {
                kept.remove(law);
            }
        }

        /** Minimal subset of {@code candidates} that contradicts together with {@code background}. */
        private List<GraphLaw> quickXplain(List<GraphLaw> background, boolean backgroundGrew, List<GraphLaw> candidates, Predicate<List<GraphLaw>> contradicting) {
            if (backgroundGrew && contradicting.test(background)) {
                return List.of();
            }
            if (candidates.size() == 1) {
                return candidates;
            }
            int half = candidates.size() / 2;
            List<GraphLaw> firstHalf = candidates.subList(0, half);
            List<GraphLaw> secondHalf = candidates.subList(half, candidates.size());
            List<GraphLaw> fromSecond = quickXplain(union(background, firstHalf), true, secondHalf, contradicting);
            List<GraphLaw> fromFirst = quickXplain(union(background, fromSecond), !fromSecond.isEmpty(), firstHalf, contradicting);
            return union(fromFirst, fromSecond);
        }

        private boolean contradicts(List<GraphLaw> laws) {
            requireTimeLeft();
            return model.check(new LinkedHashSet<>(laws)) == CountingStatus.CONTRADICTION;
        }

        private boolean contradicts(List<GraphLaw> laws, BiomeId focus) {
            requireTimeLeft();
            return model.checkFocused(new LinkedHashSet<>(laws), focus) == CountingStatus.CONTRADICTION;
        }

        private void requireTimeLeft() {
            if (System.nanoTime() > deadlineNanos) {
                throw new Incomplete();
            }
        }
    }

    /** Deadline passed, or a solver time limit kept a known contradiction from being explained: report what we have, marked incomplete. */
    private static final class Incomplete extends RuntimeException {
        Incomplete() {
            super(null, null, false, false);
        }
    }
}
