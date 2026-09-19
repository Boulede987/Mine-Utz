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
 * Real, DECOUPLED identity-only coverage proof for the {@link ToroidalGrid} (2026-09-18,
 * pivot after five straight algorithm generations of {@link ToroidalPatchConstruction}, plus
 * three targeted fixes on top, all plateaued at the same kind of budget-invariant ceiling
 * regardless of grid size or seed). Real, user-raised question that motivated this: "didn't we
 * already prove the graph was possible?" - yes, for the row-stack substrate, via {@link
 * PeriodicCoherenceProof#findWitness}. But that success does not transfer here, because
 * feasibility is a property of GRAPH + SUBSTRATE together, not the graph alone.
 *
 * <p>Real production ({@code TriangleIdentitySizeAssignment} / {@code
 * WeightedVertexPlacement}) never fuses identity and size into one search either - it solves
 * WHO'S NEXT TO WHOM first (pure graph coloring), then solves HOW BIG separately. This class
 * follows that same decomposition: one grid cell = one complete region, real identity-legal
 * adjacency ({@link RawRuleTable#isLegal} UNCONDITIONALLY) plus full 66-biome coverage.
 *
 * <p><b>v2 - real AC-3 constraint propagation:</b> the first version's 1-step forward-check
 * plateaued identically at 2,000,000 and 20,000,000 backtracks - a dead end forming two or
 * more hops away stayed invisible until deep inside an already-committed branch. Fix: maintain
 * full arc consistency - every domain is kept consistent with every OTHER domain at all times.
 * Whenever a cell is decided, propagate outward until a fixed point or a proven contradiction.
 *
 * <p><b>v3 - "most constrained VALUE" selection:</b> a diagnostic run exposed why plain MRV
 * (even AC-3-backed) still plateaued: at depth 216/256, only 26/66 biomes were covered - the
 * other 216 placements were repeats of already-used, easy-to-place biomes, pushing the 40
 * hardest (lowest-degree) missing biomes into a pile-up with EXACTLY 40 cells left for exactly
 * 40 biomes - zero slack. Fix: track, for every still-missing biome, its real "opportunity
 * count" (how many undecided cells could still legally take it) and prioritize placing the
 * scarcest one before it runs out of room, instead of letting plain cell-constrainedness decide.
 * Real, measured result: coverage now reaches "4 missing" by depth 63/256 (was depth 216
 * before), and depth scales with budget (67 at 50k, 129 at 2,000,000) instead of plateauing.
 *
 * <p><b>v4 - speed (2026-09-19), measured, not assumed:</b> v3's scarcity scan was assumed to be
 * the slowdown, so {@code opportunities.get(b)} is now maintained incrementally in lockstep
 * with every domain mutation. A timing breakdown then showed that was the wrong target:
 * {@code chooseNext} was 1.4% of runtime, AC-3 propagation 90% - the inner loop called
 * {@link RawRuleTable#isLegal} (allocates and hashes an {@code UnorderedPair}) up to 66 times per
 * value. Fixed with precomputed per-biome legal-partner sets and hoisting neighbor lookups out of
 * the per-value loop: 2.3x faster overall with a bit-identical search (same depth, same
 * propagate-call and removal counts at 50,000 backtracks). Tie-breaking among equally scarce
 * cells is explicit row-major - an unordered set's iteration order silently changed the search
 * trajectory (depth 60 vs 74 vs 67 at the same budget) until it was pinned down.
 */
final class ToroidalIdentityCoverageProof {

    /** Same order as {@link ToroidalGrid#allPositionsInFillOrder} - keeps tie-breaking among equally-scarce cells stable across remove/re-add cycles, which insertion-ordered sets do not. */
    private static final Comparator<ToroidalPos> ROW_MAJOR_ORDER = Comparator.comparingInt(ToroidalPos::r).thenComparingInt(ToroidalPos::q);

    private ToroidalIdentityCoverageProof() {
    }

    record BuildResult(Map<ToroidalPos, BiomeId> assignment, int backtracksUsed, int deepestFillCountReached) {
    }

    private record DomainRemoval(ToroidalPos pos, BiomeId value) {
    }

    private record PlacementAction(long id, ToroidalPos pos, BiomeId biome, Set<BiomeId> domainAtDecisionTime, List<DomainRemoval> propagatedRemovals) {
    }

    private record MostConstrainedChoice(ToroidalPos pos, List<BiomeId> candidates) {
    }

    /** Same real trail-scoped exclusion discipline as {@link ToroidalPatchConstruction}'s own {@code Exclusion} - see its doc for why a plain never-cleared exclusion map is a real completeness bug. */
    private record Exclusion(BiomeId biome, long validWhileActionActive) {
    }

    /** Always returns {@code Optional.of(...)} - never bare {@code Optional.empty()} - {@code assignment()==null} means failure, one shape for every caller. */
    static Optional<BuildResult> tryBuild(RawRuleTable table, ToroidalGrid grid, Random random, int backtrackBudget) {
        Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
        List<ToroidalPos> allPositions = grid.allPositionsInFillOrder();
        Deque<PlacementAction> actions = new ArrayDeque<>();
        Set<Long> activeActionIds = new HashSet<>();
        Map<ToroidalPos, List<Exclusion>> exclusionsByPosition = new HashMap<>();
        Map<BiomeId, Integer> legalNeighborCounts = computeLegalNeighborCounts(table);
        Map<BiomeId, Set<BiomeId>> legalPartners = computeLegalPartners(table);
        long[] nextActionId = {1};
        int backtracksUsed = 0;
        int deepestFillCountReached = 0;

        Map<ToroidalPos, Set<BiomeId>> domains = new HashMap<>();
        Map<BiomeId, Set<ToroidalPos>> opportunities = new HashMap<>();
        for (BiomeId biome : table.allBiomeIds()) {
            opportunities.put(biome, new HashSet<>(allPositions));
        }
        for (ToroidalPos pos : allPositions) {
            domains.put(pos, new LinkedHashSet<>(table.allBiomeIds()));
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
                undoLast(actions, assignment, activeActionIds, exclusionsByPosition, domains, opportunities);
                backtracksUsed++;
                continue;
            }

            MostConstrainedChoice choice = chooseNext(table, domains, opportunities, allPositions, assignment, activeActionIds, exclusionsByPosition, random, legalNeighborCounts);
            List<BiomeId> candidates = choice.candidates();

            boolean placedHere = false;
            for (BiomeId candidate : candidates) {
                if (!coverageStillPossible(table, grid, assignment)) {
                    break;
                }
                Set<BiomeId> domainAtDecisionTime = removeWholeDomain(domains, opportunities, choice.pos());
                assignment.put(choice.pos(), candidate);

                List<DomainRemoval> removals = new ArrayList<>();
                boolean consistent = propagateFrom(legalPartners, grid, assignment, domains, opportunities, choice.pos(), removals);

                if (!consistent || !coverageStillPossible(table, grid, assignment)) {
                    for (DomainRemoval removal : removals) {
                        addDomainValue(domains, opportunities, removal.pos(), removal.value());
                    }
                    restoreWholeDomain(domains, opportunities, choice.pos(), domainAtDecisionTime);
                    assignment.remove(choice.pos());
                    continue;
                }

                long id = nextActionId[0]++;
                actions.push(new PlacementAction(id, choice.pos(), candidate, domainAtDecisionTime, removals));
                activeActionIds.add(id);
                placedHere = true;
                break;
            }

            if (!placedHere) {
                if (actions.isEmpty() || backtracksUsed >= backtrackBudget) {
                    return Optional.of(new BuildResult(null, backtracksUsed, deepestFillCountReached));
                }
                undoLast(actions, assignment, activeActionIds, exclusionsByPosition, domains, opportunities);
                backtracksUsed++;
            }
        }
    }

    /** Adds {@code value} back into {@code domains.get(pos)} AND into {@code opportunities.get(value)} - the two structures are always kept in lockstep, never mutated independently. */
    private static void addDomainValue(Map<ToroidalPos, Set<BiomeId>> domains, Map<BiomeId, Set<ToroidalPos>> opportunities, ToroidalPos pos, BiomeId value) {
        domains.get(pos).add(value);
        opportunities.get(value).add(pos);
    }

    /** Removes {@code pos} entirely from {@code domains} (it is being decided, no longer undecided) and strips it out of every biome's opportunity set it used to belong to. Returns the removed domain, so the caller can restore it exactly later. */
    private static Set<BiomeId> removeWholeDomain(Map<ToroidalPos, Set<BiomeId>> domains, Map<BiomeId, Set<ToroidalPos>> opportunities, ToroidalPos pos) {
        Set<BiomeId> removed = domains.remove(pos);
        for (BiomeId value : removed) {
            opportunities.get(value).remove(pos);
        }
        return removed;
    }

    private static void restoreWholeDomain(Map<ToroidalPos, Set<BiomeId>> domains, Map<BiomeId, Set<ToroidalPos>> opportunities, ToroidalPos pos, Set<BiomeId> domain) {
        domains.put(pos, domain);
        for (BiomeId value : domain) {
            opportunities.get(value).add(pos);
        }
    }

    /**
     * Real AC-3 propagation (see this class's own top-level doc). {@code decidedPos} was just
     * fixed to a value - queues its still-undecided neighbors for revision, and keeps
     * propagating outward from whatever cell's domain actually shrinks, until either a fixed
     * point is reached (returns {@code true}) or some domain goes empty (a proven
     * contradiction from this decision - returns {@code false}). Every removed value is
     * appended to {@code removalsOut} so the caller can restore them exactly on backtrack.
     */
    private static boolean propagateFrom(
        Map<BiomeId, Set<BiomeId>> legalPartners, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, Map<ToroidalPos, Set<BiomeId>> domains,
        Map<BiomeId, Set<ToroidalPos>> opportunities, ToroidalPos decidedPos, List<DomainRemoval> removalsOut
    ) {
        Deque<ToroidalPos> queue = new ArrayDeque<>();
        Set<ToroidalPos> queued = new HashSet<>();
        for (ToroidalPos neighbor : grid.neighborsOf(decidedPos)) {
            if (domains.containsKey(neighbor) && queued.add(neighbor)) {
                queue.add(neighbor);
            }
        }

        while (!queue.isEmpty()) {
            ToroidalPos xi = queue.poll();
            queued.remove(xi);
            if (!domains.containsKey(xi)) {
                continue;
            }
            if (reviseDomain(legalPartners, grid, assignment, domains, opportunities, xi, removalsOut)) {
                if (domains.get(xi).isEmpty()) {
                    return false;
                }
                for (ToroidalPos neighbor : grid.neighborsOf(xi)) {
                    if (domains.containsKey(neighbor) && queued.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Removes every value from {@code domains.get(xi)} that no longer has real support - a
     * value {@code v} survives only if, for EVERY neighbor of {@code xi}, either that neighbor
     * is undecided and its own current domain still contains at least one value legal with
     * {@code v}, or the neighbor is decided and {@code v} is directly legal with it. Returns
     * whether anything was actually removed.
     */
    private static boolean reviseDomain(
        Map<BiomeId, Set<BiomeId>> legalPartners, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, Map<ToroidalPos, Set<BiomeId>> domains,
        Map<BiomeId, Set<ToroidalPos>> opportunities, ToroidalPos xi, List<DomainRemoval> removalsOut
    ) {
        Set<BiomeId> domain = domains.get(xi);
        List<ToroidalPos> neighbors = grid.neighborsOf(xi);
        // Neighbor state cannot change during this revision (only xi's own domain does), so look it up once per neighbor, not once per (value, neighbor).
        BiomeId[] decidedByNeighbor = new BiomeId[neighbors.size()];
        Set<BiomeId>[] domainByNeighbor = newDomainArray(neighbors.size());
        for (int index = 0; index < neighbors.size(); index++) {
            decidedByNeighbor[index] = assignment.get(neighbors.get(index));
            domainByNeighbor[index] = domains.get(neighbors.get(index));
        }

        boolean revised = false;
        Iterator<BiomeId> iterator = domain.iterator();
        while (iterator.hasNext()) {
            BiomeId v = iterator.next();
            Set<BiomeId> partnersOfV = legalPartners.get(v);
            if (lacksSupport(partnersOfV, decidedByNeighbor, domainByNeighbor)) {
                iterator.remove();
                opportunities.get(v).remove(xi);
                removalsOut.add(new DomainRemoval(xi, v));
                revised = true;
            }
        }
        return revised;
    }

    /** True if {@code v} (given its legal partners) has no support against at least one neighbor - a decided neighbor must be a legal partner, an undecided one must still have a legal partner left in its domain. */
    private static boolean lacksSupport(Set<BiomeId> partnersOfValue, BiomeId[] decidedByNeighbor, Set<BiomeId>[] domainByNeighbor) {
        for (int index = 0; index < decidedByNeighbor.length; index++) {
            BiomeId decided = decidedByNeighbor[index];
            if (decided != null) {
                if (!partnersOfValue.contains(decided)) {
                    return true;
                }
            } else if (!hasLegalPartner(partnersOfValue, domainByNeighbor[index])) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Set<BiomeId>[] newDomainArray(int size) {
        return (Set<BiomeId>[]) new Set<?>[size];
    }

    /** Iterates the value's own small legal-partner set (about 13 entries) and tests membership in the neighbor's domain, instead of scanning the whole domain with {@link RawRuleTable#isLegal} - which allocates and hashes an {@code UnorderedPair} per call and was 90% of measured runtime. Same answer, far fewer operations. */
    private static boolean hasLegalPartner(Set<BiomeId> legalPartnersOfValue, Set<BiomeId> neighborDomain) {
        for (BiomeId partner : legalPartnersOfValue) {
            if (neighborDomain.contains(partner)) {
                return true;
            }
        }
        return false;
    }

    /** Every biome's real legal neighbors, from {@link RawRuleTable#isLegal} itself - computed once per build, so the AC-3 inner loop never calls it. */
    private static Map<BiomeId, Set<BiomeId>> computeLegalPartners(RawRuleTable table) {
        Map<BiomeId, Set<BiomeId>> partners = new HashMap<>();
        List<BiomeId> allBiomeIds = table.allBiomeIds();
        for (BiomeId biome : allBiomeIds) {
            Set<BiomeId> legalWithBiome = new HashSet<>();
            for (BiomeId other : allBiomeIds) {
                if (table.isLegal(biome, other)) {
                    legalWithBiome.add(other);
                }
            }
            partners.put(biome, legalWithBiome);
        }
        return partners;
    }

    /** Real pigeonhole check (size doesn't apply here - each still-missing biome needs exactly ONE more cell). */
    private static boolean coverageStillPossible(RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment) {
        Set<BiomeId> used = new HashSet<>(assignment.values());
        int missingCount = 0;
        for (BiomeId biome : table.allBiomeIds()) {
            if (!used.contains(biome)) {
                missingCount++;
            }
        }
        int remainingCells = grid.cellCount() - assignment.size();
        return remainingCells >= missingCount;
    }

    /**
     * Pops the most recent action, restores every value its OWN propagation pass pruned
     * (exact, recorded trail - not a recompute), reintroduces the now-undecided cell with
     * EXACTLY the domain it had the moment it was decided ({@code domainAtDecisionTime} - real,
     * necessary care: that domain may already have been pruned by EARLIER, unrelated
     * propagation before this cell was ever chosen, so resetting to the full catalog would be
     * wrong), and records a trail-scoped exclusion for the undone value.
     */
    private static void undoLast(
        Deque<PlacementAction> actions, Map<ToroidalPos, BiomeId> assignment, Set<Long> activeActionIds,
        Map<ToroidalPos, List<Exclusion>> exclusionsByPosition, Map<ToroidalPos, Set<BiomeId>> domains, Map<BiomeId, Set<ToroidalPos>> opportunities
    ) {
        PlacementAction action = actions.pop();
        activeActionIds.remove(action.id());

        for (DomainRemoval removal : action.propagatedRemovals()) {
            addDomainValue(domains, opportunities, removal.pos(), removal.value());
        }

        assignment.remove(action.pos());
        restoreWholeDomain(domains, opportunities, action.pos(), action.domainAtDecisionTime());

        long validWhileActionActive = actions.isEmpty() ? 0L : actions.peek().id();
        exclusionsByPosition.computeIfAbsent(action.pos(), key -> new ArrayList<>()).add(new Exclusion(action.biome(), validWhileActionActive));
    }

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
     * Real "most constrained VALUE" selection - see this class's own top-level doc (v3, v4).
     * Reads {@code opportunities} directly (incrementally maintained, not recomputed) to find
     * the still-missing biome with the fewest remaining legal cells, and places it at the most
     * locally-constrained of those cells. Degrades to plain MRV once coverage is complete.
     */
    private static MostConstrainedChoice chooseNext(
        RawRuleTable table, Map<ToroidalPos, Set<BiomeId>> domains, Map<BiomeId, Set<ToroidalPos>> opportunities, List<ToroidalPos> allPositions,
        Map<ToroidalPos, BiomeId> assignment, Set<Long> activeActionIds, Map<ToroidalPos, List<Exclusion>> exclusionsByPosition,
        Random random, Map<BiomeId, Integer> legalNeighborCounts
    ) {
        Set<BiomeId> used = new HashSet<>(assignment.values());
        List<BiomeId> missing = table.allBiomeIds().stream().filter(id -> !used.contains(id)).toList();

        if (!missing.isEmpty()) {
            BiomeId scarcest = null;
            int scarcestCount = Integer.MAX_VALUE;
            for (BiomeId biome : missing) {
                int count = opportunities.get(biome).size();
                if (count < scarcestCount) {
                    scarcestCount = count;
                    scarcest = biome;
                }
            }

            if (scarcestCount == 0) {
                // real, genuine contradiction AC-3's per-cell domain-empty check can't see: a
                // still-missing biome pruned out of EVERY undecided cell's domain, one cell at
                // a time, without any single cell's domain ever itself going empty. Signal
                // failure the same way an exhausted cell does - empty candidates, real
                // backtrack.
                return new MostConstrainedChoice(null, List.of());
            }

            ToroidalPos bestPos = null;
            int bestDomainSize = Integer.MAX_VALUE;
            for (ToroidalPos pos : opportunities.get(scarcest)) {
                Set<BiomeId> excluded = currentlyExcludedAt(pos, activeActionIds, exclusionsByPosition);
                int size = (int) domains.get(pos).stream().filter(id -> !excluded.contains(id)).count();
                // Explicit row-major tie-break: the set's own iteration order is arbitrary, and an unstable choice here measurably changes how deep the search gets.
                if (size < bestDomainSize || (size == bestDomainSize && ROW_MAJOR_ORDER.compare(pos, bestPos) < 0)) {
                    bestDomainSize = size;
                    bestPos = pos;
                }
            }

            Set<BiomeId> excludedAtBest = currentlyExcludedAt(bestPos, activeActionIds, exclusionsByPosition);
            List<BiomeId> candidatesAtBest = domains.get(bestPos).stream().filter(id -> !excludedAtBest.contains(id)).toList();
            List<BiomeId> ordered = orderPreferringUnused(candidatesAtBest, assignment, random, legalNeighborCounts);
            if (ordered.contains(scarcest)) {
                List<BiomeId> reordered = new ArrayList<>(ordered);
                reordered.remove(scarcest);
                reordered.add(0, scarcest);
                ordered = reordered;
            }
            return new MostConstrainedChoice(bestPos, ordered);
        }

        MostConstrainedChoice mrv = mostConstrainedUnassigned(domains, allPositions, activeActionIds, exclusionsByPosition);
        return new MostConstrainedChoice(mrv.pos(), orderPreferringUnused(mrv.candidates(), assignment, random, legalNeighborCounts));
    }

    private static MostConstrainedChoice mostConstrainedUnassigned(
        Map<ToroidalPos, Set<BiomeId>> domains, List<ToroidalPos> allPositions, Set<Long> activeActionIds, Map<ToroidalPos, List<Exclusion>> exclusionsByPosition
    ) {
        ToroidalPos bestPos = null;
        List<BiomeId> bestCandidates = null;
        for (ToroidalPos pos : allPositions) {
            Set<BiomeId> rawCandidates = domains.get(pos);
            if (rawCandidates == null) {
                continue;
            }
            Set<BiomeId> excluded = currentlyExcludedAt(pos, activeActionIds, exclusionsByPosition);
            List<BiomeId> candidates = excluded.isEmpty()
                ? new ArrayList<>(rawCandidates)
                : rawCandidates.stream().filter(id -> !excluded.contains(id)).toList();
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

    /** Same real scarcity-ordering discipline as {@link ToroidalPatchConstruction}'s own - prefer unused biomes, rarest-adjacency ones first among them. */
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
}
