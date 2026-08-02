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
 * same legality rule as {@link GrowthEngine} — only claims a cell if this
 * biome stays legal against every OTHER neighbor that cell touches, so a
 * non-filler separator can't widen itself into a new illegal adjacency.
 * Also refuses to claim a cell if doing so would cut its donor patch into
 * two pieces (a "neck" cell) — stealing cells is fundamentally different
 * from {@link GrowthEngine} claiming untouched space, and a stolen cell
 * that severs its owner's connectivity creates exactly the kind of
 * disconnected island the rest of this pipeline goes out of its way to
 * avoid (see {@code GapFiller}'s doc comment — only stealing, never plain
 * claiming unclaimed cells, can actually cause this).
 *
 * Stops at the target size or when no more legally-claimable cells exist,
 * whichever comes first — it's a best-effort widen, not a guarantee.
 */
final class SeparatorWidener {

    private SeparatorWidener() {
    }

    static void widen(
        Set<MacroCellPos> component, BiomeId ownBiome, int targetMinCells,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph
    ) {
        if (component.size() >= targetMinCells) {
            return;
        }

        Set<MacroCellPos> claimed = new LinkedHashSet<>(component);
        TreeSet<MacroCellPos> frontier = new TreeSet<>(frontierOrder(centroidOf(component)));
        addUnclaimedNeighbors(component, claimed, regionCellSet, frontier);

        while (claimed.size() < targetMinCells && !frontier.isEmpty()) {
            MacroCellPos next = frontier.pollFirst();
            if (!isLegalToClaim(next, ownBiome, claimed, assignment, graph) || !stealingPreservesDonorConnectivity(next, assignment, regionCellSet, claimed)) {
                continue;
            }
            claimed.add(next);
            addUnclaimedNeighbors(Set.of(next), claimed, regionCellSet, frontier);
        }

        for (MacroCellPos cell : claimed) {
            assignment.put(cell, ownBiome);
        }
    }

    /**
     * Distance from the undersized patch's own centroid (fixed at the start of one
     * widen() call, not recomputed as cells get claimed) — same "grow round, not
     * toward wherever the frontier happens to sort first" fix {@link GrowthEngine}
     * already applies via its own seed-distance ordering. Without this, the plain
     * coordinate-order TreeSet claimed whichever legal cell sorted first by (x, z),
     * which systematically extends in one direction — every separator this widens
     * came out a long snake, not a blob, even once it hit its own declared minimum
     * size (reported as "tunnels, not patches" from in-game testing).
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

    /** Legal to steal this cell if the candidate biome is still allowed next to every neighbor it has outside the (growing) claimed set. */
    private static boolean isLegalToClaim(MacroCellPos cell, BiomeId candidate, Set<MacroCellPos> claimed, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            if (claimed.contains(neighbor)) {
                continue;
            }
            BiomeId neighborBiome = assignment.get(neighbor);
            if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if {@code cell} isn't a cut vertex of its current (donor) patch — i.e.
     * removing it wouldn't split the donor into two disconnected pieces. Only
     * meaningful when the donor has 2+ same-biome neighbors still outside the
     * claimed set; with 0 or 1, {@code cell} can't be bridging two other parts
     * of the patch. Checked by flood-filling from one such neighbor, through
     * donor-biome cells only, without ever stepping on {@code cell} itself,
     * and confirming that reaches every other same-biome neighbor too.
     */
    static boolean stealingPreservesDonorConnectivity(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, Set<MacroCellPos> claimed) {
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
            MacroCellPos current = stack.pop();
            for (MacroCellPos neighbor : current.orthogonalNeighbors()) {
                if (regionCellSet.contains(neighbor) && !neighbor.equals(cell) && !claimed.contains(neighbor)
                    && donorBiome.equals(assignment.get(neighbor)) && reached.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return reached.containsAll(donorNeighbors);
    }
}
