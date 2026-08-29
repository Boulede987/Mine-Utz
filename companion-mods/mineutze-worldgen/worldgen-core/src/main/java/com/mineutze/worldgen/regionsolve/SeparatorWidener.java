package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * When an undersized patch can't be legally merged into any neighbor, it's
 * not leftover cleanup — it's bridging two biomes that aren't allowed to
 * touch each other, and removing it would bring them into direct contact.
 * It still has to respect its own declared size range, though: a 1-cell
 * seam standing in for "these two aren't allowed to touch" is a visible
 * workaround, not the design (DESIGN.md never asked for a token buffer,
 * and forbidding a pair was never meant to produce one).
 *
 * Widens the patch by claiming cells from whichever patches border it,
 * same legality rule as {@link CellPlacementRules} — only claims a cell if this
 * biome stays legal against every OTHER neighbor that cell touches, so a
 * non-filler separator can't widen itself into a new illegal adjacency.
 * Also refuses to claim a cell if doing so would cut its donor patch into
 * two pieces (a "neck" cell) — stealing cells is fundamentally different
 * from {@link CellPlacementRules} claiming untouched space: claiming an unclaimed
 * cell can never disconnect an already-existing patch (there's nothing
 * there to disconnect), but stealing an already-claimed one can, if that
 * cell happened to be the donor patch's only connection between two of its
 * own halves.
 *
 * Stops at the target size or when no more legally-claimable cells exist,
 * whichever comes first — it's a best-effort widen, not a guarantee.
 */
final class SeparatorWidener {

    private SeparatorWidener() {
    }

    /**
     * Ad-hoc aggregate counters for the {@code MINEUTZE_WORLDGEN_TRACE}
     * investigation into why solving a region boxed in by real neighbor
     * context (as opposed to isolated) can cost far more than a single
     * region's own baseline — see {@code
     * RegionSolveStageTimingSpikeTest}'s 3x3-grid finding. Plain static
     * fields, not thread-local: safe because {@code SeedRegionCache} already
     * serializes all region solving behind one global lock, so exactly one
     * region's widen work is ever in flight at a time. Always incremented
     * (a handful of long additions, negligible cost) regardless of whether
     * tracing is enabled — only the eventual {@link WorldgenTrace#log} call
     * at the summary site is gated, same as everywhere else in this class.
     */
    static final class Stats {
        long widenCalls;
        long frontierPolls;
        long legalityRejects;
        long connectivityRejects;
        long connectivityChecks;
        long connectivityNodesVisited;
        long totalNanos;

        void reset() {
            widenCalls = frontierPolls = legalityRejects = connectivityRejects = connectivityChecks = connectivityNodesVisited = totalNanos = 0;
        }

        @Override
        public String toString() {
            return "widenCalls=" + widenCalls + " frontierPolls=" + frontierPolls + " legalityRejects=" + legalityRejects
                + " connectivityRejects=" + connectivityRejects + " connectivityChecks=" + connectivityChecks
                + " connectivityNodesVisited=" + connectivityNodesVisited + " widenWallMs=" + (totalNanos / 1_000_000.0);
        }
    }

    static final Stats STATS = new Stats();

    /**
     * @param repelFrom cells to actively grow AWAY from, not just toward the
     *                  component's own centroid — {@link SeamWidener}'s use case:
     *                  widening a locally-pinched buffer must not drift toward a
     *                  THIRD, unrelated incompatible biome just because it happens
     *                  to be nearest-to-centroid. Empty set (every other caller,
     *                  e.g. {@link PatchSizeEnforcer}) is exactly today's plain
     *                  centroid-only behavior — this is an additive capability,
     *                  not a rewrite. See {@link #frontierOrder}.
     */
    static void widen(
        Set<MacroCellPos> component, BiomeId ownBiome, int targetMinCells,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph,
        Set<MacroCellPos> repelFrom
    ) {
        // A caller that widens several components in one pass (SeamWidener, one call per
        // pinch cluster, all sharing one mutating assignment) can hand in a component
        // that's gone stale: an EARLIER call already legally stole one of these cells for
        // a different, now-incompatible biome. Re-verifying against the LIVE assignment
        // here, rather than trusting the caller's snapshot, is the only thing that stops
        // this call from blindly reasserting a cell straight back to ownBiome with NO
        // legality check at all (the final commit loop below only checks NEWLY claimed
        // cells, never the caller-supplied component itself) - confirmed as a real,
        // pre-existing bug via a genuine repro, not theoretical: two adjacent pinch
        // clusters (desert, forest) round-tripped a shared boundary cell back and forth,
        // leaving a live illegal adjacency in the final result.
        Set<MacroCellPos> currentComponent = new LinkedHashSet<>();
        for (MacroCellPos cell : component) {
            if (ownBiome.equals(assignment.get(cell))) {
                currentComponent.add(cell);
            }
        }
        if (currentComponent.isEmpty() || currentComponent.size() >= targetMinCells) {
            return;
        }

        long widenStart = System.nanoTime();
        STATS.widenCalls++;
        Set<MacroCellPos> claimed = new LinkedHashSet<>(currentComponent);
        TreeSet<MacroCellPos> frontier = new TreeSet<>(frontierOrder(centroidOf(currentComponent), repelFrom));
        addUnclaimedNeighbors(currentComponent, claimed, regionCellSet, frontier);

        while (claimed.size() < targetMinCells && !frontier.isEmpty()) {
            MacroCellPos next = frontier.pollFirst();
            STATS.frontierPolls++;
            if (!isLegalToClaim(next, ownBiome, claimed, assignment, graph)) {
                STATS.legalityRejects++;
                continue;
            }
            if (!stealingPreservesDonorConnectivity(next, assignment, regionCellSet, claimed)) {
                STATS.connectivityRejects++;
                continue;
            }
            claimed.add(next);
            addUnclaimedNeighbors(Set.of(next), claimed, regionCellSet, frontier);
        }

        for (MacroCellPos cell : claimed) {
            assignment.put(cell, ownBiome);
        }
        STATS.totalNanos += System.nanoTime() - widenStart;
    }

    /**
     * Primary key is distance AWAY from every {@code repelFrom} cell (negated, so
     * the farthest candidate sorts first) — 0 when {@code repelFrom} is empty, which
     * makes this degenerate to pure centroid-distance order, unchanged from before
     * this parameter existed. Secondary key is distance from the undersized patch's
     * own centroid (fixed at the start of one widen() call, not recomputed as cells
     * get claimed) — same "grow round, not toward wherever the frontier happens to
     * sort first" fix {@link CellPlacementRules} already applies via its own seed-distance
     * ordering. Without ANY distance ordering at all, the plain coordinate-order
     * TreeSet claimed whichever legal cell sorted first by (x, z), which
     * systematically extends in one direction — every separator this widens came
     * out a long snake, not a blob, even once it hit its own declared minimum size
     * (reported as "tunnels, not patches" from in-game testing).
     */
    private static Comparator<MacroCellPos> frontierOrder(MacroCellPos centroid, Set<MacroCellPos> repelFrom) {
        return Comparator
            .comparingLong((MacroCellPos cell) -> -minDistanceSquaredTo(cell, repelFrom))
            .thenComparingLong((MacroCellPos cell) -> cell.distanceSquaredTo(centroid))
            .thenComparingInt(MacroCellPos::x)
            .thenComparingInt(MacroCellPos::z);
    }

    private static long minDistanceSquaredTo(MacroCellPos cell, Set<MacroCellPos> repelFrom) {
        if (repelFrom.isEmpty()) {
            return 0;
        }
        long min = Long.MAX_VALUE;
        for (MacroCellPos source : repelFrom) {
            min = Math.min(min, cell.distanceSquaredTo(source));
        }
        return min;
    }

    private static MacroCellPos centroidOf(Set<MacroCellPos> component) {
        double averageX = component.stream().mapToInt(MacroCellPos::x).average().orElseThrow();
        double averageZ = component.stream().mapToInt(MacroCellPos::z).average().orElseThrow();
        return new MacroCellPos((int) Math.round(averageX), (int) Math.round(averageZ));
    }

    private static void addUnclaimedNeighbors(Set<MacroCellPos> from, Set<MacroCellPos> claimed, Set<MacroCellPos> regionCellSet, TreeSet<MacroCellPos> frontier) {
        for (MacroCellPos cell : from) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (regionCellSet.contains(neighbor) && !claimed.contains(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
    }

    /**
     * Legal to steal this cell if the candidate biome is still allowed (Moore, both
     * orthogonal and diagonal) next to every neighbor it has outside the (growing)
     * claimed set — see {@link CellPlacementRules#isLegal} for the shared
     * implementation. Was orthogonal-only until this delegated to that shared check -
     * a real, silent diagonal-illegal-adjacency gap, not just a style difference.
     */
    private static boolean isLegalToClaim(MacroCellPos cell, BiomeId candidate, Set<MacroCellPos> claimed, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        return CellPlacementRules.isLegalForCell(cell, claimed, candidate, assignment, graph);
    }

    /**
     * True if {@code cell} isn't a cut vertex of its current (donor) patch — i.e.
     * removing it wouldn't split the donor into two disconnected pieces. Only
     * meaningful when the donor has 2+ same-biome neighbors still outside the
     * claimed set; with 0 or 1, {@code cell} can't be bridging two other parts
     * of the patch. Checked by flood-filling from one such neighbor, through
     * donor-biome cells only, without ever stepping on {@code cell} itself,
     * and confirming that reaches every other same-biome neighbor too.
     *
     * <p>Exits the flood-fill the moment every {@code donorNeighbors} cell has
     * been reached, rather than exhaustively exploring the rest of the (possibly
     * enormous) donor patch first — at production region scale a donor patch can
     * be hundreds of thousands of cells, and {@code widen} calls this once per
     * candidate steal, so an ordinary edge-of-patch steal (reachable again within
     * a handful of steps, going around the removed cell) must not pay for
     * flooding the whole remaining patch just to confirm what's already known.
     * Confirmed via jstack: without this, a single {@code widen} call stealing
     * thousands of cells one at a time out of one dominant donor (an undersized
     * patch whose real minimum, in cells, is now huge post cell-size-4 - see
     * {@code MacroGridConfig#cellCountForWidthBlocks}) pegged one core for 20+
     * minutes. Only a genuine near-cut-vertex - where reaching the other
     * neighbors really does require searching most of the patch - still pays
     * close to the full cost, which is correct: that's exactly the case this
     * check exists to get right.
     */
    static boolean stealingPreservesDonorConnectivity(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, Set<MacroCellPos> claimed) {
        STATS.connectivityChecks++;
        BiomeId donorBiome = assignment.get(cell);
        if (donorBiome == null) {
            return true;
        }

        List<MacroCellPos> donorNeighbors = cell.orthogonalNeighbors().stream()
            .filter(n -> regionCellSet.contains(n) && !claimed.contains(n) && donorBiome.equals(assignment.get(n)))
            .toList();
        if (donorNeighbors.size() <= 1) {
            return true;
        }

        Set<MacroCellPos> reached = new HashSet<>();
        Deque<MacroCellPos> stack = new ArrayDeque<>(List.of(donorNeighbors.get(0)));
        reached.add(donorNeighbors.get(0));
        while (!stack.isEmpty()) {
            if (reached.containsAll(donorNeighbors)) {
                STATS.connectivityNodesVisited += reached.size();
                return true;
            }
            MacroCellPos current = stack.pop();
            for (MacroCellPos neighbor : current.orthogonalNeighbors()) {
                if (regionCellSet.contains(neighbor) && !neighbor.equals(cell) && !claimed.contains(neighbor)
                    && donorBiome.equals(assignment.get(neighbor)) && reached.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        STATS.connectivityNodesVisited += reached.size();
        return reached.containsAll(donorNeighbors);
    }
}
