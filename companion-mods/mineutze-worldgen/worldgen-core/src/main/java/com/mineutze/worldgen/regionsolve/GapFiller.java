package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Seed growth (see {@link GrowthEngine}) rarely tiles a region perfectly —
 * patches can leave gaps between them where neither neighbor's growth
 * reached. Grows every already-assigned patch simultaneously into the
 * unclaimed territory (multi-source BFS / discrete Voronoi): each gap cell
 * ends up owned by whichever patch's frontier reaches it first, subject to
 * the same legality ({@link GrowthEngine#isLegal}) and shape-identity
 * ({@link GrowthEngine#satisfiesCohesion}) checks {@link GrowthEngine}
 * itself uses. Any cell no patch's frontier reached with enough cohesion to
 * claim it (boxed between mutually-incompatible neighbors, or just far from
 * everything) falls to {@link #resolveRemaining} — see its own doc.
 *
 * Previously a per-cell majority vote among immediate neighbors only, no
 * lookahead, no shape check — a fundamentally different (and more myopic)
 * model than {@link GrowthEngine}'s for no real reason, and very plausibly
 * a source of the same thin/winding shapes {@link GrowthCohesionProfile}
 * was built to fix in ordinary growth: a gap boxed between two different
 * patches could flip which one "wins" cell-by-cell rather than being
 * claimed coherently by whichever is actually closest.
 */
final class GapFiller {

    // Same technique as GrowthEngine's own frontier jitter (JITTER_MAGNITUDE/SALT
    // there) — deterministic per (worldSeed, cell, biome), so competing patches at
    // equal distance resolve reproducibly instead of by insertion order, and the
    // resulting boundary reads as organic rather than a hard bias toward whichever
    // biome sorts first alphabetically.
    private static final double JITTER_MAGNITUDE = 12.0;
    private static final long JITTER_SALT = 0x6A9F_11E4L;

    private GapFiller() {
    }

    private record FillCandidate(MacroCellPos cell, BiomeId biome, int distance) {
    }

    /**
     * Never leaves a cell unresolved and never throws out of a real world-gen call
     * (see doc/KNOWN_ISSUES.md) - a graph misconfiguration is caught eagerly at
     * {@link BiomeGraph} construction time instead (every biome pair must be
     * bridgeable within {@link BiomeGraph}'s own hop budget), so anything left over
     * here is pure runtime donor scarcity: the bridge the graph promises exists,
     * there just wasn't enough nearby room to fit it at this exact spot. Escalates
     * through three tiers, cheapest/least-disruptive first, each one only tried
     * once the previous is exhausted:
     * <ol>
     *   <li>{@link #makeLegalHere} - reassign a short, size/connectivity-safe chain
     *   of neighbors (bounded depth). Free of visible side effects when it works.
     *   <li>{@link #dropSmallestBlockingPatch} - undo the smallest directly-
     *   blocking patch entirely, as if never placed, and let it get reclaimed
     *   naturally through a fresh {@link #runFillPass}. Capped at {@link
     *   #MAX_PATCH_DROP_RETRIES} - see its own doc for why that's safe to cap
     *   rather than needing to be unbounded.
     *   <li>{@code resolveRemaining}'s {@code force} mode - the unconditional
     *   guarantee. Directly commits whichever biome best fits the dead-end cell's
     *   neighbors, ignoring legality entirely, rather than another indirect pass
     *   that a later fill could silently undo (a real bug an earlier version of
     *   this method had: clearing the blocking neighbors and hoping the next
     *   {@link #runFillPass} wouldn't just refill them with the exact same
     *   biomes - it usually did, since nothing outside those few cells had
     *   changed). Committing the cell's own value directly, once, is immune to
     *   that - {@code resolveRemaining} never revisits a cell already in {@code
     *   assignment}.
     * </ol>
     */
    static void fillGaps(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph, long worldSeed) {
        int patchDropAttempt = 0;

        while (true) {
            runFillPass(assignment, regionCellSet, grid, graph, worldSeed);

            try {
                resolveRemaining(assignment, regionCellSet, grid, graph, false);
                return;
            } catch (DeadEndException deadEnd) {
                if (patchDropAttempt < MAX_PATCH_DROP_RETRIES && dropSmallestBlockingPatch(deadEnd.cell, assignment, regionCellSet)) {
                    patchDropAttempt++;
                    continue;
                }
                // Guaranteed last resort (see this method's own doc, tier 3) - directly
                // and permanently resolves every still-unassigned cell, including this
                // one, no further fill passes needed.
                resolveRemaining(assignment, regionCellSet, grid, graph, true);
                return;
            }
        }
    }

    private static void runFillPass(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph, long worldSeed) {
        PriorityQueue<FillCandidate> frontier = new PriorityQueue<>(candidateOrder(worldSeed));

        // Seed from every already-assigned cell, including ones outside regionCellSet
        // (fixedBoundaryCells, i.e. an already-solved neighbor region's edge) — its
        // biome is legitimate source context for cells just inside our own region,
        // same as GrowthEngine and the old majority-vote both already treated it.
        // addUnclaimedRegionNeighbors below still only ever pushes/claims cells
        // actually inside regionCellSet. Re-run in full on every retry (see fillGaps)
        // — already-assigned cells are skipped as soon as they're polled, so redoing
        // this costs nothing extra beyond whatever was just freed up.
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            addUnclaimedRegionNeighbors(entry.getKey(), entry.getValue(), 1, assignment, regionCellSet, frontier);
        }

        while (!frontier.isEmpty()) {
            FillCandidate candidate = frontier.poll();
            MacroCellPos cell = candidate.cell();
            if (assignment.containsKey(cell)) {
                continue; // already claimed by a competing biome that reached it first
            }

            GrowthCohesionProfile cohesion = graph.node(candidate.biome()).cohesionProfile();
            boolean stillLegal = GrowthEngine.isLegal(cell, candidate.biome(), assignment, graph);
            boolean stillCohesive = GrowthEngine.satisfiesCohesion(cell, candidate.biome(), assignment, cohesion);
            if (!stillLegal || !stillCohesive) {
                // Legality can only get MORE restrictive as neighbors fill in, so an entry
                // valid when pushed can go stale by the time it's popped - recheck fresh
                // rather than trusting push-time state. Not a dead end: some other source
                // may still legally/cohesively reach this cell later, and if none ever
                // does, the final sweep below (resolveRemaining) resolves it directly.
                continue;
            }

            assignment.put(cell, candidate.biome());
            addUnclaimedRegionNeighbors(cell, candidate.biome(), candidate.distance() + 1, assignment, regionCellSet, frontier);
        }
    }

    // Sanity cap on how many whole patches one fillGaps call will sacrifice to reach a
    // legal state, same role as MAX_REASSIGN_DEPTH - bounds the search, not a
    // correctness mechanism. makeLegalHere (cell-level, non-destructive) is always
    // tried first; this only fires once that's already exhausted for every candidate
    // biome at a cell. Safe to cap (rather than needing to be unbounded) precisely
    // because resolveRemaining's force mode is waiting behind it as an unconditional
    // guarantee - this tier only needs to handle the cases worth the quality
    // tradeoff of trying a bit harder before falling back to that.
    private static final int MAX_PATCH_DROP_RETRIES = 3;

    /**
     * A dead end isn't always a graph misconfiguration - sometimes it's just that
     * {@code deadEndCell}'s immediate neighbors happen to include two (or more)
     * mutually-incompatible patches that both grew right up to this exact point,
     * and no bounded {@link #makeLegalHere} reshuffle could bridge them locally
     * (e.g. the bridge exists in the graph but needs more donor capacity nearby
     * than this specific spot has left - see doc/KNOWN_ISSUES.md). Rather than
     * force a choice or relax a rule, undo the SMALLEST of the directly-blocking
     * patches entirely — exactly as if it had never been placed, same as a demand
     * {@link RegionSolver} couldn't find room for in the first place — and let the
     * next {@link #fillGaps} retry reclaim that space for whichever neighbor is
     * actually still legal there. Smallest first: least disruptive to the overall
     * placement, and most likely to be a small/awkward fringe rather than the
     * region's main patch of that biome.
     *
     * Never touches a cell outside {@code regionCellSet} - a blocking neighbor
     * that's part of an already-solved neighbor region's fixed boundary cannot be
     * undone here, so a patch whose only presence at this cell is that fixed
     * boundary is skipped entirely (not droppable). Returns false (nothing
     * dropped) if every directly-blocking neighbor turns out to be fixed-
     * boundary-only - the caller falls through to {@link
     * #forceClearBlockingNeighbors} in that case, same as when this tier's own
     * retry budget runs out.
     */
    private static boolean dropSmallestBlockingPatch(MacroCellPos deadEndCell, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet) {
        Set<MacroCellPos> smallestDroppable = null;
        for (MacroCellPos neighbor : deadEndCell.orthogonalNeighbors()) {
            if (!regionCellSet.contains(neighbor) || assignment.get(neighbor) == null) {
                continue;
            }
            Set<MacroCellPos> component = componentContaining(neighbor, assignment, regionCellSet);
            if (smallestDroppable == null || component.size() < smallestDroppable.size()) {
                smallestDroppable = component;
            }
        }

        if (smallestDroppable == null) {
            return false;
        }
        smallestDroppable.forEach(assignment::remove);
        return true;
    }

    /** Same-biome flood fill from {@code start}, restricted to {@code regionCellSet} - mirrors {@link SeparatorOrganicizer#donorComponentSizeWithout}'s own traversal. */
    private static Set<MacroCellPos> componentContaining(MacroCellPos start, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet) {
        BiomeId biome = assignment.get(start);
        Set<MacroCellPos> visited = new LinkedHashSet<>();
        Deque<MacroCellPos> stack = new ArrayDeque<>(List.of(start));
        visited.add(start);
        while (!stack.isEmpty()) {
            MacroCellPos current = stack.pop();
            for (MacroCellPos neighbor : current.orthogonalNeighbors()) {
                if (regionCellSet.contains(neighbor) && biome.equals(assignment.get(neighbor)) && visited.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return visited;
    }

    private static final class DeadEndException extends RuntimeException {
        final MacroCellPos cell;

        DeadEndException(MacroCellPos cell, String message) {
            super(message);
            this.cell = cell;
        }

        IllegalStateException toIllegalStateException(String extra) {
            return new IllegalStateException(getMessage() + extra);
        }
    }

    /**
     * No node is universally legal (see {@link BiomeGraph}'s own doc — the earlier
     * "universal filler" guarantee was removed on purpose), so a cell the BFS never
     * claimed needs a real resolution, not a guaranteed-safe default. Processed in a
     * fixed deterministic order, each pick immediately visible to later cells in the
     * same sweep (so a small cluster of leftover cells still resolves coherently
     * relative to each other, not just relative to the BFS's own output).
     *
     * For each cell: among every biome that's actually legal there right now
     * (checked graph-wide, not just among its neighbors — cohesion is a shape
     * preference the BFS already tried and failed to satisfy, not a hard
     * constraint, so it's dropped for this last resort), pick whichever has the
     * most already-assigned matching neighbors, tie-broken by natural {@link
     * BiomeId} order for full determinism. A cell with zero assigned neighbors at
     * all (every candidate legal, vacuously, none preferred) just takes the
     * tie-break — nothing to inform the choice from, not a conflict.
     *
     * If NO biome is legal at a cell given its CURRENT neighbors, that's not
     * necessarily a dead end — {@link #makeLegalHere} looks for a nearby chain of
     * neighbor reassignments (bounded depth — see its own doc) that would open up
     * a legal choice here. Only once that also fails does it throw {@link
     * DeadEndException} - unless {@code force} is set, in which case it commits
     * the best available fit anyway (see {@link #bestLegalChoice}'s own doc). See
     * {@link #fillGaps} for how the two tiers above this one, and this method's
     * own {@code force} mode, fit together.
     */
    private static void resolveRemaining(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph, boolean force) {
        List<MacroCellPos> remaining = regionCellSet.stream()
            .filter(cell -> !assignment.containsKey(cell))
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();

        for (MacroCellPos cell : remaining) {
            assignment.put(cell, bestLegalChoice(cell, assignment, regionCellSet, grid, graph, force));
        }
    }

    private static BiomeId bestLegalChoice(
        MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph, boolean force
    ) {
        List<BiomeId> legalCandidates = legalCandidatesAt(cell, assignment, graph);

        if (legalCandidates.isEmpty()) {
            List<BiomeId> allBiomeIds = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
            for (BiomeId candidate : allBiomeIds) {
                if (makeLegalHere(cell, candidate, assignment, regionCellSet, new HashSet<>(), 0, grid, graph)) {
                    legalCandidates = List.of(candidate);
                    break;
                }
            }
        }

        Map<BiomeId, Long> neighborCounts = cell.orthogonalNeighbors().stream()
            .map(assignment::get)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(biome -> biome, Collectors.counting()));

        if (legalCandidates.isEmpty()) {
            if (force) {
                // The unconditional guarantee (see fillGaps's doc, tier 3): every other
                // option exhausted, so commit whichever biome is already the best local
                // fit even though it's not actually legal here, rather than crash a real
                // game's world generation over one macro-cell. Deliberately last, not
                // first - the two tiers above resolve the vast majority of real dead
                // ends without ever reaching this, and an already-placed patch shouldn't
                // casually lose the size/connectivity guarantees the other tiers respect.
                return graph.nodes().stream()
                    .map(BiomeNode::id)
                    .max(Comparator.<BiomeId>comparingLong(id -> neighborCounts.getOrDefault(id, 0L)).thenComparing(Comparator.naturalOrder()))
                    .orElseThrow();
            }
            throw new DeadEndException(
                cell,
                "GapFiller: no biome in the graph is legal at " + cell + " given its neighbors " + neighborCounts
                    + " - the adjacency graph is over-constrained (e.g. mutually-incompatible biomes meeting at "
                    + "a point with nothing compatible with all of them), and no neighbor could be legally "
                    + "reassigned to fix that either."
            );
        }

        return legalCandidates.stream()
            .max(Comparator.<BiomeId>comparingLong(id -> neighborCounts.getOrDefault(id, 0L)).thenComparing(Comparator.naturalOrder()))
            .orElseThrow();
    }

    private static List<BiomeId> legalCandidatesAt(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        return graph.nodes().stream()
            .map(BiomeNode::id)
            .filter(candidate -> GrowthEngine.isLegal(cell, candidate, assignment, graph))
            .toList();
    }

    // Sanity cap on reassignment chain length, same role as BudgetPlanner.MAX_DRAWS -
    // bounds the search, not a correctness mechanism. 2 is enough for the real case
    // that motivated this (a biome directly blocked by a neighbor that's itself only
    // reassignable by fixing ONE of ITS OWN neighbors first - forest surrounded by
    // more forest, one layer out, blocking what would otherwise be a one-hop fix).
    // Not general backtracking (that was tried before this solver's current design
    // and abandoned for fighting the wrong problem, see doc/ARCHITECTURE.md) - a
    // conflict needing a longer chain still fails loud rather than searching
    // indefinitely.
    private static final int MAX_REASSIGN_DEPTH = 2;

    /**
     * Ensures {@code desiredBiome} can legally sit at {@code position} — either it
     * already can (base case), or EVERY blocking neighbor gets reassigned in turn,
     * each to something that (a) is itself compatible with {@code desiredBiome},
     * unblocking it, and (b) can recursively be made legal there in turn, up to
     * {@link #MAX_REASSIGN_DEPTH} — a cell touching more than one incompatible
     * biome at once needs all of them resolved, not just whichever comes first in
     * {@link MacroCellPos#orthogonalNeighbors()} order; fixing one and declaring
     * victory while another still blocks {@code position} would leave a real
     * illegal adjacency on the board. {@code cell} in {@link #bestLegalChoice}
     * calls this once per candidate biome at depth 0; every recursive call one
     * layer out is fixing a DIFFERENT, more specific problem (make THIS blocking
     * neighbor accept THIS exact biome), not re-deriving the original cell's
     * problem.
     *
     * {@code beingReassigned} prevents a cell from being reassigned twice in the
     * same chain (would risk undoing an earlier layer's fix or cycling forever
     * within the depth cap). Never touches a cell outside {@code regionCellSet} -
     * that would mean mutating an already-solved neighbor region's cached, fixed
     * boundary, which every other pass in this solver goes out of its way to never
     * do.
     *
     * A reassignment only counts as safe if the donor patch (the neighbor's
     * CURRENT biome) survives it: still connected (reuses {@link
     * SeparatorWidener#stealingPreservesDonorConnectivity}) and still at or above
     * its own declared minimum size (reuses {@link
     * SeparatorOrganicizer#donorComponentSizeWithout}/{@code minCellsFor}) - an
     * already-placed patch does not get quietly shrunk below what its own graph
     * entry requires just to bail out a neighboring cell. Reverts on any failed
     * branch — including a branch where SOME blocking neighbors got fixed but
     * {@code position} still isn't legal once every neighbor's been tried — so a
     * caller that gets {@code false} back is guaranteed {@code assignment} is
     * exactly as it found it.
     */
    private static boolean makeLegalHere(
        MacroCellPos position, BiomeId desiredBiome, Map<MacroCellPos, BiomeId> assignment,
        Set<MacroCellPos> regionCellSet, Set<MacroCellPos> beingReassigned, int depth, MacroGridConfig grid, BiomeGraph graph
    ) {
        if (GrowthEngine.isLegal(position, desiredBiome, assignment, graph)) {
            return true;
        }
        if (depth >= MAX_REASSIGN_DEPTH) {
            return false;
        }

        Map<MacroCellPos, BiomeId> fixedThisCall = new LinkedHashMap<>();
        for (MacroCellPos neighbor : position.orthogonalNeighbors()) {
            if (GrowthEngine.isLegal(position, desiredBiome, assignment, graph)) {
                break; // an earlier neighbor fixed within this same call already unblocked position
            }
            if (!regionCellSet.contains(neighbor) || beingReassigned.contains(neighbor)) {
                continue;
            }
            BiomeId blocking = assignment.get(neighbor);
            if (blocking == null || graph.isAllowed(desiredBiome, blocking)) {
                continue; // not assigned, or not actually blocking desiredBiome - nothing to fix here
            }
            if (!SeparatorWidener.stealingPreservesDonorConnectivity(neighbor, assignment, regionCellSet, Set.of())) {
                continue;
            }
            int minCells = SeparatorOrganicizer.minCellsFor(blocking, grid, graph);
            if (SeparatorOrganicizer.donorComponentSizeWithout(neighbor, blocking, assignment, regionCellSet) < minCells) {
                continue;
            }

            for (BiomeId alternative : graph.nodes().stream().map(BiomeNode::id).sorted().toList()) {
                if (alternative.equals(blocking) || !graph.isAllowed(desiredBiome, alternative)) {
                    continue; // must actually unblock desiredBiome, not just be a different biome
                }

                assignment.put(neighbor, alternative);
                beingReassigned.add(neighbor);
                if (makeLegalHere(neighbor, alternative, assignment, regionCellSet, beingReassigned, depth + 1, grid, graph)) {
                    fixedThisCall.put(neighbor, blocking);
                    break;
                }
                assignment.put(neighbor, blocking);
                beingReassigned.remove(neighbor);
            }
        }

        if (GrowthEngine.isLegal(position, desiredBiome, assignment, graph)) {
            return true;
        }

        // Every blocking neighbor was tried (or none were fixable) and position still
        // isn't legal - a partial fix isn't a fix. Undo every reassignment made across
        // ALL neighbors touched in this call, not just the last one.
        for (Map.Entry<MacroCellPos, BiomeId> fixed : fixedThisCall.entrySet()) {
            assignment.put(fixed.getKey(), fixed.getValue());
            beingReassigned.remove(fixed.getKey());
        }
        return false;
    }

    private static void addUnclaimedRegionNeighbors(
        MacroCellPos cell, BiomeId biome, int distance, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, PriorityQueue<FillCandidate> frontier
    ) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            if (regionCellSet.contains(neighbor) && !assignment.containsKey(neighbor)) {
                frontier.add(new FillCandidate(neighbor, biome, distance));
            }
        }
    }

    private static Comparator<FillCandidate> candidateOrder(long worldSeed) {
        return Comparator
            .comparingDouble((FillCandidate candidate) -> candidate.distance() + jitter(worldSeed, candidate.cell(), candidate.biome()))
            .thenComparingInt(candidate -> candidate.cell().x())
            .thenComparingInt(candidate -> candidate.cell().z())
            .thenComparing(candidate -> candidate.biome().toString());
    }

    /** Deterministic: same (worldSeed, cell, biome) always jitters the same way, so the fill stays reproducible. */
    private static double jitter(long worldSeed, MacroCellPos cell, BiomeId biome) {
        long hash = worldSeed ^ JITTER_SALT;
        hash = hash * 31 + cell.x();
        hash = hash * 31 + cell.z();
        hash = hash * 31 + biome.toString().hashCode();
        return new Random(hash).nextDouble() * JITTER_MAGNITUDE;
    }
}
