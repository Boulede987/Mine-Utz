package com.mineutze.worldgen.regionsolve.pixelgrid;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

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
 * Export-only today, alongside its real caller {@link PatchSizeEnforcer} - see that
 * class's doc.
 *
 * <p>When an undersized patch can't be legally merged into any neighbor, it's not
 * leftover cleanup - it's bridging two biomes not allowed to touch, and removing it
 * would bring them into direct contact. It still has to respect its own declared size
 * range, though: a 1-cell seam standing in for "these two aren't allowed to touch" is a
 * visible workaround, not the design (DESIGN.md never asked for a token buffer, and
 * forbidding a pair was never meant to produce one).
 *
 * <p>Widens the patch by claiming cells from whichever patches border it, same
 * legality rule as {@link CellPlacementRules} - only claims a cell if this biome stays
 * legal against every OTHER neighbor that cell touches, so a non-filler separator can't
 * widen itself into a new illegal adjacency. Also refuses to claim a cell if doing so
 * would cut its donor patch into two pieces (a "neck" cell) - stealing cells is
 * fundamentally different from {@link CellPlacementRules} claiming untouched space:
 * claiming an unclaimed cell can never disconnect an already-existing patch (nothing
 * there to disconnect), but stealing an already-claimed one can, if that cell happened
 * to be the donor patch's only connection between two of its own halves.
 *
 * <p>Stops at the target size or when no more legally-claimable cells exist, whichever
 * comes first - a best-effort widen, not a guarantee.
 */
final class SeparatorWidener {

    private SeparatorWidener() {
    }

    /**
     * Ad-hoc aggregate counters for the {@code MINEUTZE_WORLDGEN_TRACE} investigation
     * into why solving a region boxed in by real neighbor context (vs. isolated) can
     * cost far more than a single region's own baseline - see {@code
     * RegionSolveStageTimingSpikeTest}'s 3x3-grid finding. Plain static fields, not
     * thread-local: safe because {@code SeedRegionCache} already serializes all region
     * solving behind one global lock, so exactly one region's widen work is ever in
     * flight at a time. Always incremented (a handful of long additions, negligible
     * cost) regardless of whether tracing is enabled - only the eventual {@link
     * WorldgenTrace#log} call at the summary site is gated, same as everywhere else in
     * this class.
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

    static void widen(
        Set<MacroCellPos> component, BiomeId ownBiome, int targetMinCells,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph
    ) {
        Set<MacroCellPos> currentComponent = liveComponent(component, ownBiome, assignment);
        if (currentComponent.isEmpty() || currentComponent.size() >= targetMinCells) {
            return;
        }

        long widenStart = System.nanoTime();
        STATS.widenCalls++;
        Set<MacroCellPos> claimed = new LinkedHashSet<>(currentComponent);
        TreeSet<MacroCellPos> frontier = new TreeSet<>(frontierOrder(centroidOf(currentComponent)));
        addUnclaimedNeighbors(currentComponent, claimed, regionCellSet, frontier);

        claimUntilTargetOrFrontierExhausted(claimed, frontier, targetMinCells, ownBiome, assignment, regionCellSet, graph);

        commit(claimed, ownBiome, assignment);
        STATS.totalNanos += System.nanoTime() - widenStart;
    }

    /**
     * Re-verifies {@code component} against the LIVE assignment rather than trusting the
     * caller's snapshot - a caller that widens several components in one pass can hand
     * in a component that's gone stale: an EARLIER call already legally stole one of
     * these cells for a different, now-incompatible biome. Without this, {@link #commit}
     * (which only checks NEWLY claimed cells, never the caller-supplied component
     * itself) would blindly reassert a cell straight back to {@code ownBiome} with NO
     * legality check - confirmed as a real, pre-existing bug via a genuine repro, not
     * theoretical: two adjacent pinch clusters (desert, forest) round-tripped a shared
     * boundary cell back and forth, leaving a live illegal adjacency in the final result.
     */
    private static Set<MacroCellPos> liveComponent(Set<MacroCellPos> component, BiomeId ownBiome, Map<MacroCellPos, BiomeId> assignment) {
        Set<MacroCellPos> currentComponent = new LinkedHashSet<>();
        for (MacroCellPos cell : component) {
            if (ownBiome.equals(assignment.get(cell))) {
                currentComponent.add(cell);
            }
        }
        return currentComponent;
    }

    /** Repeatedly claims the frontier's nearest-to-centroid legal, donor-connectivity-safe cell until {@code claimed} reaches {@code targetMinCells} or the frontier runs dry - see {@link #isLegalToClaim} and {@link #stealingPreservesDonorConnectivity} for what each check guards against. */
    private static void claimUntilTargetOrFrontierExhausted(
        Set<MacroCellPos> claimed, TreeSet<MacroCellPos> frontier, int targetMinCells, BiomeId ownBiome,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph
    ) {
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
    }

    private static void commit(Set<MacroCellPos> claimed, BiomeId ownBiome, Map<MacroCellPos, BiomeId> assignment) {
        for (MacroCellPos cell : claimed) {
            assignment.put(cell, ownBiome);
        }
    }

    /**
     * Distance from the undersized patch's own centroid (fixed at the start of one
     * widen() call, not recomputed as cells get claimed) - same "grow round, not toward
     * wherever the frontier happens to sort first" fix {@link CellPlacementRules}
     * already applies via its own seed-distance ordering. Without this, the plain
     * coordinate-order TreeSet claimed whichever legal cell sorted first by (x, z),
     * which systematically extends in one direction - every separator this widens came
     * out a long snake, not a blob, even once it hit its own declared minimum size
     * (reported as "tunnels, not patches" from in-game testing).
     */
    private static Comparator<MacroCellPos> frontierOrder(MacroCellPos centroid) {
        return Comparator
            .comparingLong((MacroCellPos cell) -> cell.distanceSquaredTo(centroid))
            .thenComparingInt(MacroCellPos::x)
            .thenComparingInt(MacroCellPos::z);
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
     * claimed set - see {@link CellPlacementRules#isLegal} for the shared
     * implementation. Was orthogonal-only until delegated to that shared check - a
     * real, silent diagonal-illegal-adjacency gap, not just a style difference.
     */
    private static boolean isLegalToClaim(MacroCellPos cell, BiomeId candidate, Set<MacroCellPos> claimed, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        return CellPlacementRules.isLegalForCell(cell, claimed, candidate, assignment, graph);
    }

    /**
     * True if {@code cell} isn't a cut vertex of its current (donor) patch - i.e.
     * removing it wouldn't split the donor into two disconnected pieces. Only
     * meaningful when the donor has 2+ same-biome neighbors still outside the claimed
     * set; with 0 or 1, {@code cell} can't be bridging two other parts of the patch.
     */
    static boolean stealingPreservesDonorConnectivity(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, Set<MacroCellPos> claimed) {
        STATS.connectivityChecks++;
        BiomeId donorBiome = assignment.get(cell);
        if (donorBiome == null) {
            return true;
        }

        List<MacroCellPos> donorNeighbors = donorNeighborsOutsideClaimed(cell, donorBiome, assignment, regionCellSet, claimed);
        if (donorNeighbors.size() <= 1) {
            return true;
        }
        return reachesEveryNeighborWithoutCrossing(cell, donorBiome, donorNeighbors, assignment, regionCellSet, claimed);
    }

    /** Same-biome orthogonal neighbors of {@code cell} still real donor territory - inside the region, not already claimed by this {@code widen()} call, and actually {@code donorBiome}. */
    private static List<MacroCellPos> donorNeighborsOutsideClaimed(
        MacroCellPos cell, BiomeId donorBiome, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, Set<MacroCellPos> claimed
    ) {
        return cell.orthogonalNeighbors().stream()
            .filter(neighbor -> regionCellSet.contains(neighbor) && !claimed.contains(neighbor) && donorBiome.equals(assignment.get(neighbor)))
            .toList();
    }

    /**
     * Flood-fills from one donor neighbor, through donor-biome cells only, without ever
     * stepping on {@code cell} itself, checking whether that reaches every other entry
     * in {@code donorNeighbors} too - {@code cell} is a cut vertex exactly when it
     * doesn't.
     *
     * <p>Exits the moment every {@code donorNeighbors} cell has been reached, rather
     * than exhaustively exploring the rest of the (possibly enormous) donor patch first
     * - at production region scale a donor patch can be hundreds of thousands of cells,
     * and {@code widen} calls this once per candidate steal, so an ordinary
     * edge-of-patch steal (reachable again within a handful of steps, going around the
     * removed cell) must not pay for flooding the whole remaining patch just to confirm
     * what's already known. Confirmed via jstack: without this, a single {@code widen}
     * call stealing thousands of cells one at a time out of one dominant donor (an
     * undersized patch whose real minimum, in cells, is now huge post cell-size-4 - see
     * {@code MacroGridConfig#cellCountForWidthBlocks}) pegged one core for 20+ minutes.
     * Only a genuine near-cut-vertex - where reaching the other neighbors really does
     * require searching most of the patch - still pays close to the full cost, which is
     * correct: that's exactly the case this check exists to get right.
     */
    private static boolean reachesEveryNeighborWithoutCrossing(
        MacroCellPos cell, BiomeId donorBiome, List<MacroCellPos> donorNeighbors,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, Set<MacroCellPos> claimed
    ) {
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
