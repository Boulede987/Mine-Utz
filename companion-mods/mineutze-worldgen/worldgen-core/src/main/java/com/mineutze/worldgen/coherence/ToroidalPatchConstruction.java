package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Real, sequential construction of a {@link ToroidalGrid} patch (2026-09-18, v5 - real
 * unification, not a parameter tweak). History: v1 (position-ordered fill, MAX enforced, MIN
 * only checked post-hoc) failed near-instantly, zero coverage. v2 (needs-driven min-pursuit +
 * unused-biome bias) got PROVABLY stuck regardless of backtrack budget (identical stuck point
 * at 50,000 and 1,000,000 backtracks). v3 (demand-ordered two-phase: seed+grow every biome to
 * its min FIRST, then plain-fill the rest) fixed that, but exposed a new problem: Phase 2 got
 * stuck on gap cells surrounded by several already-FIXED Phase 1 clusters with no jointly-
 * legal biome, and Phase 2's own backtracking could never reach back into Phase 1 (frozen by
 * the time Phase 2 ran). v4 (single backtrack stack shared by both stages, so backtracking
 * COULD reach back into seeding) fixed THAT specific freeze - real, measured: backtrack
 * counts started scaling with grid size instead of exhausting after 1-3 - but every run still
 * died at the exact same point, right at the seed-to-fill boundary. v4.1 (rank seed positions
 * by how constrained they already are, instead of first-legal-in-shuffled-order) did not move
 * that wall either - real, measured: 16x16 hit genuine exhaustion (13,102 backtracks used of
 * a 30,000 budget - stopped because it ran out of things to try, not because it ran out of
 * patience). Four straight variants dying at the exact same structural point (the seed/fill
 * boundary itself) was the real signal: the boundary, not the heuristics on either side of
 * it, was the problem.
 *
 * <p><b>v5 fix:</b> no more seed stage and fill stage at all - ONE real MRV loop over the
 * whole grid from the start. At the most-constrained still-undecided cell, try each legal
 * candidate biome (not-yet-used ones preferred, for coverage) and unconditionally run {@link
 * #growToMinimum} on whatever cluster that placement just became - a safe no-op when the
 * cluster is already at or above its minimum, real needs-driven recruitment when it is not.
 * There is no separate "seed" concept left to freeze: every decision, whether it starts a
 * brand new biome or extends one already in progress, goes on the SAME backtrack stack, so a
 * later dead end can always unwind all the way back through it. Completion no longer needs a
 * separate minimum check either - {@link #growToMinimum} already guarantees any biome that
 * made it into the assignment reached its own minimum at the moment it was placed, so "grid
 * full" only still needs a coverage check (all 66 biomes present).
 */
final class ToroidalPatchConstruction {

    private ToroidalPatchConstruction() {
    }

    record BuildResult(Map<ToroidalPos, BiomeId> assignment, int backtracksUsed, int deepestFillCountReached) {
    }

    private record PlacementAction(long id, ToroidalPos primaryPos, BiomeId biome, List<ToroidalPos> cells) {
    }

    private record MostConstrainedChoice(ToroidalPos pos, List<BiomeId> candidates) {
    }

    /**
     * Real trail-scoped exclusion (2026-09-18, real correctness fix after v5's scarcity-bias
     * fix STILL hit genuine, non-budget-limited exhaustion at 16x16 - suspicious, since coverage
     * of 66 biomes across 256 real cells should not be structurally impossible). Root cause: a
     * plain {@code Map<ToroidalPos,Set<BiomeId>>} exclusion map, once an entry is added, NEVER
     * gets cleared - so an exclusion recorded because of a LATER failure (something downstream
     * of this position didn't work out) stays in force even after backtracking has since undone
     * everything that made it necessary, permanently barring a candidate that might be perfectly
     * legal in the NEW context. This is classic chronological-backtracking INCOMPLETENESS - the
     * search can report "no solution" when one genuinely exists. Real fix: every exclusion
     * remembers WHICH action was on top of the stack when it was recorded ({@code
     * validWhileActionActive}); it only still applies while that exact action remains on the
     * stack (tracked via {@code activeActionIds}) - once the stack unwinds past it, the
     * exclusion is stale and is treated as if it were never recorded.
     */
    private record Exclusion(BiomeId biome, long validWhileActionActive) {
    }

    /** Always returns {@code Optional.of(...)} - never bare {@code Optional.empty()} - {@code assignment()==null} means failure, one shape for every caller. */
    static Optional<BuildResult> tryBuild(RawRuleTable table, ToroidalGrid grid, double baselineFaceAreaBlocks2, Random random, int backtrackBudget) {
        Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
        List<ToroidalPos> allPositions = grid.allPositionsInFillOrder();
        Deque<PlacementAction> actions = new ArrayDeque<>();
        Set<Long> activeActionIds = new HashSet<>();
        Map<ToroidalPos, List<Exclusion>> exclusionsByPosition = new HashMap<>();
        Map<BiomeId, Integer> legalNeighborCounts = computeLegalNeighborCounts(table);
        Map<BiomeId, Integer> cellsNeededForMinByBiome = computeCellsNeededForMinByBiome(table, baselineFaceAreaBlocks2);
        long[] nextActionId = {1};
        int backtracksUsed = 0;
        int deepestFillCountReached = 0;

        Map<ToroidalPos, List<BiomeId>> candidateCache = new HashMap<>();
        for (ToroidalPos pos : allPositions) {
            candidateCache.put(pos, candidatesFor(table, grid, pos, assignment, baselineFaceAreaBlocks2));
        }

        while (true) {
            deepestFillCountReached = Math.max(deepestFillCountReached, assignment.size());

            if (assignment.size() == grid.cellCount()) {
                if (new HashSet<>(assignment.values()).containsAll(table.allBiomeIds())) {
                    return Optional.of(new BuildResult(Map.copyOf(assignment), backtracksUsed, deepestFillCountReached));
                }
                if (actions.isEmpty() || backtracksUsed >= backtrackBudget) {
                    return Optional.of(new BuildResult(null, backtracksUsed, deepestFillCountReached));
                }
                undoLast(table, grid, baselineFaceAreaBlocks2, actions, assignment, activeActionIds, exclusionsByPosition, candidateCache);
                backtracksUsed++;
                continue;
            }

            MostConstrainedChoice choice = mostConstrainedUnassigned(candidateCache, allPositions, activeActionIds, exclusionsByPosition);
            List<BiomeId> candidates = orderPreferringUnused(choice.candidates(), assignment, random, legalNeighborCounts);

            boolean placedHere = false;
            for (BiomeId candidate : candidates) {
                assignment.put(choice.pos(), candidate);
                Optional<List<ToroidalPos>> recruited = growToMinimum(table, grid, choice.pos(), candidate, assignment, baselineFaceAreaBlocks2);
                if (recruited.isEmpty()) {
                    assignment.remove(choice.pos());
                    continue;
                }
                List<ToroidalPos> cells = new ArrayList<>(recruited.get());
                cells.add(choice.pos());

                if (!coverageStillPossible(table, grid, assignment, cellsNeededForMinByBiome)) {
                    for (ToroidalPos pos : cells) {
                        assignment.remove(pos);
                    }
                    continue;
                }

                Set<ToroidalPos> newCluster = ToroidalClusterMath.clusterOf(grid, choice.pos(), candidate, assignment);
                if (hasDeadNeighbor(table, grid, assignment, baselineFaceAreaBlocks2, newCluster)) {
                    for (ToroidalPos pos : cells) {
                        assignment.remove(pos);
                    }
                    continue;
                }

                long id = nextActionId[0]++;
                actions.push(new PlacementAction(id, choice.pos(), candidate, cells));
                activeActionIds.add(id);

                for (ToroidalPos pos : cells) {
                    candidateCache.remove(pos);
                }
                refreshCacheAroundCluster(table, grid, assignment, baselineFaceAreaBlocks2, candidateCache, newCluster);

                placedHere = true;
                break;
            }

            if (!placedHere) {
                if (actions.isEmpty() || backtracksUsed >= backtrackBudget) {
                    return Optional.of(new BuildResult(null, backtracksUsed, deepestFillCountReached));
                }
                undoLast(table, grid, baselineFaceAreaBlocks2, actions, assignment, activeActionIds, exclusionsByPosition, candidateCache);
                backtracksUsed++;
            }
        }
    }

    /**
     * Pops the single most recent action off the shared stack, undoes every cell it placed
     * (including any active recruitment), records a trail-scoped exclusion for {@code
     * (primaryPos, biome)} (see {@link Exclusion}'s own doc), and repairs {@code
     * candidateCache} for everything the removal could have affected.
     */
    private static void undoLast(
        RawRuleTable table, ToroidalGrid grid, double baselineFaceAreaBlocks2, Deque<PlacementAction> actions, Map<ToroidalPos, BiomeId> assignment,
        Set<Long> activeActionIds, Map<ToroidalPos, List<Exclusion>> exclusionsByPosition, Map<ToroidalPos, List<BiomeId>> candidateCache
    ) {
        PlacementAction action = actions.pop();
        activeActionIds.remove(action.id());

        Set<ToroidalPos> oldCluster = ToroidalClusterMath.clusterOf(grid, action.primaryPos(), action.biome(), assignment);
        for (ToroidalPos pos : action.cells()) {
            assignment.remove(pos);
        }
        for (ToroidalPos pos : action.cells()) {
            candidateCache.put(pos, candidatesFor(table, grid, pos, assignment, baselineFaceAreaBlocks2));
        }
        refreshCacheAroundCluster(table, grid, assignment, baselineFaceAreaBlocks2, candidateCache, oldCluster);

        long validWhileActionActive = actions.isEmpty() ? 0L : actions.peek().id();
        exclusionsByPosition.computeIfAbsent(action.primaryPos(), key -> new ArrayList<>()).add(new Exclusion(action.biome(), validWhileActionActive));
    }

    /**
     * Real, cheap 1-step forward-check (2026-09-18, real re-attempt of an idea tried and
     * dropped earlier this session: v4's own forward-checking was too expensive back then - a
     * full O(cellCount x 66) scan PER CANDIDATE TRIED, which caused a multi-minute stall and
     * was removed. It is cheap NOW because {@code candidateCache} already exists for MRV's own
     * sake - this reuses the exact same bounded cluster-neighbor scan {@link
     * #refreshCacheAroundCluster} already does, just computed BEFORE committing instead of
     * after, so a placement that would leave some neighbor with zero real legal candidates can
     * be rejected immediately and a different candidate tried, instead of being discovered
     * only much later (real, repeated evidence this session: three separate runs each plateaued
     * with the search 87-99% complete, never early - exactly what happens when a late-forming
     * dead cell isn't caught until it's deep in an already-committed branch).
     */
    private static boolean hasDeadNeighbor(
        RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, double baselineFaceAreaBlocks2, Set<ToroidalPos> cluster
    ) {
        for (ToroidalPos member : cluster) {
            for (ToroidalPos neighbor : grid.neighborsOf(member)) {
                if (!assignment.containsKey(neighbor) && candidatesFor(table, grid, neighbor, assignment, baselineFaceAreaBlocks2).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Real incremental cache repair (2026-09-18, real fix for MRV's dominant per-iteration
     * cost: re-deriving EVERY still-undecided cell's full 66-candidate legality list on EVERY
     * single decision, even for cells nowhere near what just changed - real, measured: 859s of
     * wall time to exhaust 200,000 backtracks at 32x32). A cell's real candidate list only
     * changes when one of ITS neighbors' values changes - but the MAX-width check
     * (isWithinMax) looks at a candidate's WHOLE connected same-biome cluster, not just the
     * immediate neighbor, so a position several hops away through a same-biome chain can also
     * be affected (real catalog clusters are small though - at most ~5 cells for this
     * catalog's biggest max-width biomes - so this stays cheap). Re-derives the real candidate
     * list ONLY for still-undecided neighbors of every cell in {@code cluster} - correct and
     * bounded, not proportional to grid size.
     */
    private static void refreshCacheAroundCluster(
        RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, double baselineFaceAreaBlocks2,
        Map<ToroidalPos, List<BiomeId>> candidateCache, Set<ToroidalPos> cluster
    ) {
        for (ToroidalPos member : cluster) {
            for (ToroidalPos neighbor : grid.neighborsOf(member)) {
                if (!assignment.containsKey(neighbor)) {
                    candidateCache.put(neighbor, candidatesFor(table, grid, neighbor, assignment, baselineFaceAreaBlocks2));
                }
            }
        }
    }

    /**
     * Real candidates still excluded at {@code pos} right now. Self-pruning (2026-09-18, real
     * fix for a measured superlinear slowdown: a 750,000-backtrack run took 6,364s - about
     * 10x worse per-backtrack than a linear scale-up from the 200,000-backtrack run's 163s
     * would predict). Root cause: this used to only FILTER stale (trail-invalidated) entries
     * at read time without ever removing them, so a position revisited many times over a long
     * run accumulated an ever-growing list of mostly-dead exclusion entries, every one of
     * them rescanned on every single read - real unbounded growth, not a fixed cost. Now
     * physically removes stale entries as they're found (via the iterator), so list size
     * stays bounded by how many exclusions are CURRENTLY valid for this position (small,
     * bounded by active stack depth), not how many were ever recorded across the whole run.
     */
    private static Set<BiomeId> currentlyExcludedAt(ToroidalPos pos, Set<Long> activeActionIds, Map<ToroidalPos, List<Exclusion>> exclusionsByPosition) {
        List<Exclusion> exclusions = exclusionsByPosition.get(pos);
        if (exclusions == null) {
            return Set.of();
        }
        Set<BiomeId> excluded = new HashSet<>();
        Iterator<Exclusion> iterator = exclusions.iterator();
        while (iterator.hasNext()) {
            Exclusion exclusion = iterator.next();
            if (exclusion.validWhileActionActive() == 0L || activeActionIds.contains(exclusion.validWhileActionActive())) {
                excluded.add(exclusion.biome());
            } else {
                iterator.remove();
            }
        }
        if (exclusions.isEmpty()) {
            exclusionsByPosition.remove(pos);
        }
        return excluded;
    }

    /**
     * Scans every still-undecided position (via the incrementally-maintained {@code
     * candidateCache}, NOT a fresh recompute - see {@link #refreshCacheAroundCluster}),
     * returns the one with the FEWEST legal candidates (ties: first found) - real MRV
     * heuristic. Stops early on a zero-candidate position - cannot get more constrained than
     * that.
     */
    private static MostConstrainedChoice mostConstrainedUnassigned(
        Map<ToroidalPos, List<BiomeId>> candidateCache, List<ToroidalPos> allPositions,
        Set<Long> activeActionIds, Map<ToroidalPos, List<Exclusion>> exclusionsByPosition
    ) {
        ToroidalPos bestPos = null;
        List<BiomeId> bestCandidates = null;
        for (ToroidalPos pos : allPositions) {
            List<BiomeId> rawCandidates = candidateCache.get(pos);
            if (rawCandidates == null) {
                continue;
            }
            Set<BiomeId> excluded = currentlyExcludedAt(pos, activeActionIds, exclusionsByPosition);
            List<BiomeId> candidates = excluded.isEmpty() ? rawCandidates : rawCandidates.stream().filter(id -> !excluded.contains(id)).toList();
            if (bestCandidates == null || candidates.size() < bestCandidates.size()) {
                bestPos = pos;
                bestCandidates = candidates;
                if (candidates.isEmpty()) {
                    break;
                }
            }
        }
        return new MostConstrainedChoice(bestPos, bestCandidates);
    }

    /**
     * Real, necessary companion to needs-driven min-pursuit - a real measured side effect:
     * satisfying one biome's minimum greedily consumes many cells at once, so plain catalog-
     * order iteration crowds out variety fast. Try NOT-YET-GLOBALLY-USED biomes first (real,
     * ordered among themselves by SCARCITY - see below), already-used ones after (randomly
     * ordered - no real reason to prefer one already-covered biome over another). Recomputes
     * {@code globallyUsed} fresh from the CURRENT assignment each call - never incrementally
     * tracked - a DISCARDED trial must never pollute the "already covered" set.
     *
     * <p><b>Scarcity ordering (2026-09-18, real fix after v5's first measured run):</b> "prefer
     * unused" alone was NOT enough - real, measured: 31-32/66 biomes consistently missing at
     * every genuine (non-budget-limited) exhaustion, the SAME set every run. Real cause,
     * confirmed against the actual catalog: those missing biomes have measurably LOWER real
     * adjacency degree (avg 11.16 real legal neighbor types vs 15.32 for the ones that made
     * it in) - widely-compatible ("generalist") biomes were winning ties against narrowly-
     * compatible ("specialist") ones whenever both were simultaneously unused-and-legal, so
     * specialists kept losing their few compatible slots to generalists and got starved out
     * entirely by the time the grid filled. Fix: among still-unused candidates, prefer the
     * one with FEWER real legal neighbor types in the whole catalog first - spend a
     * specialist's rare opportunity when it appears, instead of letting an easy-to-place
     * generalist take it.
     */
    private static List<BiomeId> orderPreferringUnused(
        List<BiomeId> candidates, Map<ToroidalPos, BiomeId> assignment, Random random, Map<BiomeId, Integer> legalNeighborCounts
    ) {
        Set<BiomeId> globallyUsed = new HashSet<>(assignment.values());
        List<BiomeId> unused = new ArrayList<>(candidates.stream().filter(id -> !globallyUsed.contains(id)).toList());
        List<BiomeId> used = new ArrayList<>(candidates.stream().filter(globallyUsed::contains).toList());
        Collections.shuffle(unused, random);
        Collections.shuffle(used, random);
        unused.sort(Comparator.comparingInt(legalNeighborCounts::get));
        List<BiomeId> ordered = new ArrayList<>(unused);
        ordered.addAll(used);
        return ordered;
    }

    /**
     * Real pigeonhole/capacity pruning (2026-09-18 - real fix for a measured, dominant waste
     * pattern: every grid size was reliably filling to 100% before discovering coverage was
     * broken, burning thousands of real cell-decisions in a branch that was ALREADY doomed
     * partway through). For every biome not yet placed anywhere, its own {@link
     * #computeCellsNeededForMinByBiome} says the smallest real cell count it could possibly
     * occupy. If the number of still-undecided cells is already less than the SUM of that
     * requirement over every still-missing biome, full coverage is now mathematically
     * impossible from here - a real, cheap (O(66)) lower bound, checked right after every
     * placement so this branch gets abandoned the MOMENT it becomes hopeless, not 1,000 cells
     * later. A necessary, not sufficient, condition (a real relaxation - ignores adjacency
     * legality entirely) - the existing full coverage check at 100% fill remains the real,
     * exact backstop.
     */
    private static boolean coverageStillPossible(
        RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, Map<BiomeId, Integer> cellsNeededForMinByBiome
    ) {
        Set<BiomeId> used = new HashSet<>(assignment.values());
        int minCellsStillNeeded = 0;
        for (BiomeId biome : table.allBiomeIds()) {
            if (!used.contains(biome)) {
                minCellsStillNeeded += cellsNeededForMinByBiome.get(biome);
            }
        }
        int remainingCells = grid.cellCount() - assignment.size();
        return remainingCells >= minCellsStillNeeded;
    }

    /** The smallest real cell count whose width already reaches each biome's own declared minimum - catalog data only, computed once per {@link #tryBuild} call. */
    private static Map<BiomeId, Integer> computeCellsNeededForMinByBiome(RawRuleTable table, double baselineFaceAreaBlocks2) {
        Map<BiomeId, Integer> result = new HashMap<>();
        for (BiomeId biome : table.allBiomeIds()) {
            int minWidthBlocks = table.sizeRangeOf(biome).minWidthBlocks();
            int cellCount = 1;
            while (ToroidalClusterMath.clusterWidthBlocks(cellCount, baselineFaceAreaBlocks2) < minWidthBlocks) {
                cellCount++;
            }
            result.put(biome, cellCount);
        }
        return result;
    }

    /** Real per-biome count of how many OTHER catalog biomes it is legal next to - computed once per {@link #tryBuild} call, not the live power-diagram/legality code, just {@link RawRuleTable#isLegal} itself over every real pair. */
    private static Map<BiomeId, Integer> computeLegalNeighborCounts(RawRuleTable table) {
        Map<BiomeId, Integer> counts = new HashMap<>();
        List<BiomeId> allBiomeIds = table.allBiomeIds();
        for (BiomeId biome : allBiomeIds) {
            int count = 0;
            for (BiomeId other : allBiomeIds) {
                if (!biome.equals(other) && table.isLegal(biome, other)) {
                    count++;
                }
            }
            counts.put(biome, count);
        }
        return counts;
    }

    private static List<BiomeId> candidatesFor(
        RawRuleTable table, ToroidalGrid grid, ToroidalPos pos, Map<ToroidalPos, BiomeId> assignment, double baselineFaceAreaBlocks2
    ) {
        List<ToroidalPos> neighbors = grid.neighborsOf(pos);
        List<BiomeId> candidates = new ArrayList<>();
        for (BiomeId candidate : table.allBiomeIds()) {
            if (!isIdentityLegal(table, candidate, neighbors, assignment)) {
                continue;
            }
            if (!isWithinMax(table, grid, pos, candidate, assignment, baselineFaceAreaBlocks2)) {
                continue;
            }
            candidates.add(candidate);
        }
        return candidates;
    }

    /**
     * Real "needs-driven self-adjacency," ported from {@code
     * reachability.SizeAwareLocalClusterSolver.growToMinimum}. {@code pos} is already
     * committed to {@code biome} in {@code assignment} by the caller before this is invoked.
     * Safe to call unconditionally (v5) - a no-op returning {@code Optional.of(List.of())}
     * when the cluster {@code pos} landed in is already at or above its minimum. Recruits are
     * written directly into {@code assignment}; on failure every recruit made THIS call is
     * undone before returning empty ({@code pos} itself is the caller's own responsibility to
     * undo, not touched here).
     */
    private static Optional<List<ToroidalPos>> growToMinimum(
        RawRuleTable table, ToroidalGrid grid, ToroidalPos seed, BiomeId biome, Map<ToroidalPos, BiomeId> assignment, double baselineFaceAreaBlocks2
    ) {
        Set<ToroidalPos> cluster = new LinkedHashSet<>(ToroidalClusterMath.clusterOf(grid, seed, biome, assignment));
        List<ToroidalPos> recruited = new ArrayList<>();
        int minWidthBlocks = table.sizeRangeOf(biome).minWidthBlocks();
        int maxWidthBlocks = table.sizeRangeOf(biome).maxWidthBlocks();

        Deque<ToroidalPos> frontier = new ArrayDeque<>();
        Set<ToroidalPos> queued = new HashSet<>(cluster);
        for (ToroidalPos member : cluster) {
            for (ToroidalPos neighbor : grid.neighborsOf(member)) {
                if (queued.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }

        while (ToroidalClusterMath.clusterWidthBlocks(cluster.size(), baselineFaceAreaBlocks2) < minWidthBlocks) {
            ToroidalPos recruit = null;
            while (!frontier.isEmpty()) {
                ToroidalPos candidate = frontier.poll();
                if (assignment.containsKey(candidate)) {
                    continue; // already decided to something else - permanently ineligible
                }
                if (!isIdentityLegal(table, biome, grid.neighborsOf(candidate), assignment)) {
                    continue; // permanently ineligible - legality against its OTHER neighbors never improves
                }
                if (ToroidalClusterMath.clusterWidthBlocks(cluster.size() + 1, baselineFaceAreaBlocks2) > maxWidthBlocks) {
                    continue; // permanently ineligible - cluster only grows from here, never shrinks
                }
                recruit = candidate;
                break;
            }
            if (recruit == null) {
                for (ToroidalPos alreadyRecruited : recruited) {
                    assignment.remove(alreadyRecruited);
                }
                return Optional.empty();
            }
            assignment.put(recruit, biome);
            recruited.add(recruit);
            cluster.add(recruit);
            for (ToroidalPos neighbor : grid.neighborsOf(recruit)) {
                if (queued.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
        return Optional.of(recruited);
    }

    /** Same-biome adjacency always allowed - deliberately different from {@link RawRuleTable#isLegal}'s unconditional X-X-illegal rule, exactly the same reasoning as {@code reachability.SizeAwareLocalClusterSolver}'s own identity check. */
    private static boolean isIdentityLegal(RawRuleTable table, BiomeId candidate, List<ToroidalPos> neighbors, Map<ToroidalPos, BiomeId> assignment) {
        for (ToroidalPos neighbor : neighbors) {
            BiomeId neighborValue = assignment.get(neighbor);
            if (neighborValue != null && !candidate.equals(neighborValue) && !table.isLegal(candidate, neighborValue)) {
                return false;
            }
        }
        return true;
    }

    /** Temporarily commits {@code candidate} at {@code pos} to measure the resulting cluster - always removed before returning, safe (single-threaded, no concurrent access). */
    private static boolean isWithinMax(
        RawRuleTable table, ToroidalGrid grid, ToroidalPos pos, BiomeId candidate, Map<ToroidalPos, BiomeId> assignment, double baselineFaceAreaBlocks2
    ) {
        assignment.put(pos, candidate);
        try {
            int cellCount = ToroidalClusterMath.clusterOf(grid, pos, candidate, assignment).size();
            double widthBlocks = ToroidalClusterMath.clusterWidthBlocks(cellCount, baselineFaceAreaBlocks2);
            return widthBlocks <= table.sizeRangeOf(candidate).maxWidthBlocks();
        } finally {
            assignment.remove(pos);
        }
    }
}
