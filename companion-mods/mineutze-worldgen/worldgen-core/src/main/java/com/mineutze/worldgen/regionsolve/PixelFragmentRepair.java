package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fixes two discretization artifacts a macro-cell grid can end up with even
 * when every upstream pass (mesh legality, growth, gap-fill) was itself
 * correct: a cell illegally adjacent to a neighbor (orthogonal or diagonal),
 * or a same-biome component at or under {@link #SMALL_FRAGMENT_MAX_SIZE}
 * cells — a stray speck, not a real patch.
 *
 * <p>Two live callers, same method, different {@code fixedContext} — the
 * parameter is what makes both safe:
 * <ul>
 *   <li>{@link MeshGeometryRealization} calls this once per region, with an
 *   EMPTY {@code fixedContext} — purely an interior discretization cleanup
 *   over that region's own just-rasterized cells (see its own doc: a
 *   rasterization-stranded speck is a property of THIS region's own mesh,
 *   unrelated to neighbor solve order). This is NOT the region-scoped,
 *   boundary-aware repair that was tried and reverted earlier — that one
 *   passed real {@code fixedBoundaryCells} in and still couldn't catch a
 *   violation against a neighbor that hadn't solved yet (a real,
 *   order-dependent gap, inherent to "regions solve once, commit forever,
 *   never get force-solved") — measured directly on a 5x5 export: left 12
 *   of 12 stray small fragments untouched. That specific use case (boundary
 *   context feeding a per-region call) is still correctly not attempted.
 *   <li>The export CLI's {@code RegionGridSolver} calls this once over an
 *   entire already-combined multi-region swath, also with an EMPTY {@code
 *   fixedContext} (every cell is "ownable" from a whole-swath view) —
 *   something only a finite, one-shot export can do, catching cross-region
 *   defects no single region's own solve could see.
 * </ul>
 *
 * <p>{@code ownCells} are the only cells ever reassigned; {@code fixedContext}
 * (nonempty only if a future caller ever has real neighbor context worth
 * reading without owning) is read-only ground truth a caller is never
 * allowed to revise — same "own the boundary, never touch a fixed neighbor
 * cell" discipline the rest of this pipeline follows.
 */
public final class PixelFragmentRepair {

    // Sanity cap on repair passes - bounds the search, not a correctness mechanism.
    // A cell left illegal after this many passes is logged and accepted rather than
    // forced (0-58/9216 cells historically, never silently hidden).
    private static final int MAX_REPAIR_PASSES = 20;

    // Small enough that merging a whole fragment into its majority neighbor is never
    // visible as its own "patch" (a real patch minimum is enforced separately, by
    // PatchSizeEnforcer) - this constant only needs to catch discretization debris,
    // not decide what a legitimate small patch looks like.
    private static final int SMALL_FRAGMENT_MAX_SIZE = 4;

    private PixelFragmentRepair() {
    }

    /**
     * A cell that's either illegal against an orthogonal/diagonal neighbor, or
     * part of a same-biome component at or under {@link #SMALL_FRAGMENT_MAX_SIZE}
     * cells, gets reassigned to whichever biome IS legal against every current
     * neighbor and has the most matching neighbors among those. Iterates to a
     * fixed point (a repair can itself create or expose a new violation nearby),
     * capped at {@link #MAX_REPAIR_PASSES}.
     *
     * <p>Scoped narrowly on purpose: only ever reassigns a cell that's already
     * illegal, or part of a too-small component, using the graph's own real
     * bridge paths (every non-adjacent pair is guaranteed bridgeable within
     * {@code BiomeGraph}'s own hop budget, checked eagerly at construction) —
     * never touches a cell that's already legal and part of a real-sized patch.
     */
    public static Map<MacroCellPos, BiomeId> repair(
        Map<MacroCellPos, BiomeId> ownCells, Map<MacroCellPos, BiomeId> fixedContext, BiomeGraph graph
    ) {
        Map<MacroCellPos, BiomeId> repaired = new LinkedHashMap<>(ownCells);
        for (int pass = 0; pass < MAX_REPAIR_PASSES; pass++) {
            Map<MacroCellPos, BiomeId> withContext = new LinkedHashMap<>(fixedContext);
            withContext.putAll(repaired);

            List<MacroCellPos> illegalCells = findIllegalCells(repaired, withContext, graph);
            List<Set<MacroCellPos>> smallFragments = findSmallFragmentComponents(repaired, withContext);
            if (illegalCells.isEmpty() && smallFragments.isEmpty()) {
                break;
            }
            for (MacroCellPos cell : illegalCells) {
                bestLegalReplacement(cell, withContext, graph).ifPresent(replacement -> {
                    repaired.put(cell, replacement);
                    WorldgenTrace.log("PIXELREPAIR reassign " + cell + " = " + replacement);
                });
            }
            for (Set<MacroCellPos> fragment : smallFragments) {
                bestLegalReplacementForFragment(fragment, withContext, graph).ifPresent(replacement -> {
                    for (MacroCellPos cell : fragment) {
                        repaired.put(cell, replacement);
                    }
                    WorldgenTrace.log("PIXELREPAIR merge fragment " + fragment + " = " + replacement);
                });
            }
        }
        return repaired;
    }

    /** @param ownCells only these cells are ever candidates for reassignment; {@code context} (own cells + fixed context) is only ever read, to see what they're actually touching. */
    private static List<MacroCellPos> findIllegalCells(Map<MacroCellPos, BiomeId> ownCells, Map<MacroCellPos, BiomeId> context, BiomeGraph graph) {
        List<MacroCellPos> illegal = new ArrayList<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : ownCells.entrySet()) {
            if (!isLegalIncludingDiagonals(entry.getKey(), entry.getValue(), context, graph)) {
                illegal.add(entry.getKey());
            }
        }
        return illegal;
    }

    /**
     * Component size is measured over {@code context} (own cells + fixed
     * context) — a fragment that looks small from the own-cells view alone
     * might actually be the edge of a large patch continuing into fixed
     * territory, which must NOT get merged away. Only the OWN portion of a
     * genuinely-small component is ever returned, since a fixed context cell
     * can never be reassigned.
     */
    private static List<Set<MacroCellPos>> findSmallFragmentComponents(Map<MacroCellPos, BiomeId> ownCells, Map<MacroCellPos, BiomeId> context) {
        List<Set<MacroCellPos>> fragments = new ArrayList<>();
        Set<MacroCellPos> visited = new HashSet<>();
        for (MacroCellPos start : ownCells.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            Set<MacroCellPos> component = sameBiomeComponent(start, context);
            visited.addAll(component);
            if (component.size() > SMALL_FRAGMENT_MAX_SIZE) {
                continue;
            }
            Set<MacroCellPos> ownPortion = new LinkedHashSet<>(component);
            ownPortion.retainAll(ownCells.keySet());
            if (!ownPortion.isEmpty()) {
                fragments.add(ownPortion);
            }
        }
        return fragments;
    }

    private static Set<MacroCellPos> sameBiomeComponent(MacroCellPos start, Map<MacroCellPos, BiomeId> assignment) {
        BiomeId biome = assignment.get(start);
        Set<MacroCellPos> visited = new HashSet<>();
        Deque<MacroCellPos> stack = new ArrayDeque<>(List.of(start));
        visited.add(start);
        while (!stack.isEmpty()) {
            MacroCellPos current = stack.pop();
            for (MacroCellPos neighbor : current.orthogonalNeighbors()) {
                if (biome.equals(assignment.get(neighbor)) && visited.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return visited;
    }

    private static Optional<BiomeId> bestLegalReplacement(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<BiomeId> legalCandidates = graph.nodes().stream()
            .map(BiomeNode::id)
            .filter(candidate -> isLegalIncludingDiagonals(cell, candidate, assignment, graph))
            .toList();
        if (legalCandidates.isEmpty()) {
            return Optional.empty();
        }
        Map<BiomeId, Long> neighborCounts = cell.orthogonalNeighbors().stream()
            .map(assignment::get)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(biome -> biome, Collectors.counting()));
        return legalCandidates.stream()
            .max(Comparator.<BiomeId>comparingLong(id -> neighborCounts.getOrDefault(id, 0L)).thenComparing(Comparator.naturalOrder()));
    }

    /**
     * Same tie-break as {@link #bestLegalReplacement} (most matching neighbors among
     * legal candidates), but evaluated over the WHOLE fragment's external perimeter at
     * once rather than one cell's neighbors — a candidate must be legal against every
     * cell bordering the fragment from outside, and the fragment's own internal cells
     * are excluded from that check (merging them together erases the boundary between
     * them, there's nothing left to validate there).
     */
    private static Optional<BiomeId> bestLegalReplacementForFragment(Set<MacroCellPos> fragment, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<BiomeId> legalCandidates = graph.nodes().stream()
            .map(BiomeNode::id)
            .filter(candidate -> fragmentAcceptsCandidate(fragment, candidate, assignment, graph))
            .toList();
        if (legalCandidates.isEmpty()) {
            return Optional.empty();
        }
        Map<BiomeId, Long> neighborCounts = fragment.stream()
            .flatMap(cell -> cell.orthogonalNeighbors().stream())
            .filter(neighbor -> !fragment.contains(neighbor))
            .map(assignment::get)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(biome -> biome, Collectors.counting()));
        return legalCandidates.stream()
            .max(Comparator.<BiomeId>comparingLong(id -> neighborCounts.getOrDefault(id, 0L)).thenComparing(Comparator.naturalOrder()));
    }

    private static boolean fragmentAcceptsCandidate(Set<MacroCellPos> fragment, BiomeId candidate, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        for (MacroCellPos cell : fragment) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (fragment.contains(neighbor)) {
                    continue;
                }
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                    return false;
                }
            }
            for (MacroCellPos diagonal : cell.diagonalNeighbors()) {
                if (fragment.contains(diagonal)) {
                    continue;
                }
                BiomeId diagonalBiome = assignment.get(diagonal);
                if (diagonalBiome != null && !graph.isAllowed(candidate, diagonalBiome)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** {@link GrowthEngine#isLegal} only checks the 4 orthogonal neighbors (same as the rest of this pipeline) — this repair pass also checks diagonals, since a discretization-born violation has no reason to prefer one over the other. */
    private static boolean isLegalIncludingDiagonals(MacroCellPos cell, BiomeId candidate, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        if (!GrowthEngine.isLegal(cell, candidate, assignment, graph)) {
            return false;
        }
        for (MacroCellPos diagonal : cell.diagonalNeighbors()) {
            BiomeId diagonalBiome = assignment.get(diagonal);
            if (diagonalBiome != null && !graph.isAllowed(candidate, diagonalBiome)) {
                return false;
            }
        }
        return true;
    }
}
