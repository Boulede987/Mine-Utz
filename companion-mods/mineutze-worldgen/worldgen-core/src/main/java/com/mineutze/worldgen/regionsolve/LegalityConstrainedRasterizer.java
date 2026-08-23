package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The FINAL rasterization pass — replaces a plain global weighted-nearest search
 * (still alive as {@code TriangleFieldGeometryRealization.rasterizeFaceWinners}, kept as
 * a cheap legality-UNAWARE measurement tool for {@code correctRadii}'s own internal area
 * counting) with one that GUARANTEES zero illegal adjacent-cell biome pairs in its
 * output, Moore (8-way) neighbors included — a hard requirement, not a rate to minimize.
 *
 * <p><b>Why the plain search can't guarantee this</b>: it picks each cell's winner
 * independently, over every {@code relevantFaceIndices} candidate, deliberately not
 * restricted to {@link com.mineutze.worldgen.regionsolve.topology.TriangleAdjacency}'s
 * Delaunay edges (that cap is CSP-legality-tractability-only, never meant to bound
 * rendering reach — see {@code TriangleFieldGeometryRealization.rasterizeFaceWinners}'s
 * own doc). So two faces the CSP never legality-checked against each other (only real
 * Delaunay-adjacent pairs are) can end up winning two geometrically-adjacent cells.
 *
 * <p><b>The mechanism</b>: legality-constrained greedy growth, not a repair pass over
 * already-decided output — every cell's value is decided exactly once, at generation
 * time, using only what's already been decided before it (this region's own growing
 * result, plus already-committed cross-region context from {@code
 * RegionSolveInput#fixedBoundaryCells()}), never revisited afterward.
 * <ol>
 *   <li>Every cell gets a priority key: its own best UNCONSTRAINED score (same
 *   distance-minus-radius rule the plain search uses) over {@code relevantFaceIndices}.
 *   This is ONLY an ordering heuristic, not the final decision — see below.
 *   <li>Cells are grown out of a priority queue in ascending-score order — "most
 *   confident first." The overwhelming majority of interior cells have one dominant,
 *   unambiguous winner and resolve essentially for free (their top candidate is almost
 *   always already legal, often trivially so because a same-biome neighbor is already
 *   decided); this concentrates all real risk into genuinely ambiguous seam cells, which
 *   by the time they're popped have the most neighbor context available to constrain
 *   them. Ties broken by a fixed, seed-independent total order ({@link MacroCellPos#x()}
 *   then {@link MacroCellPos#z()}) — never {@link PriorityQueue}'s own tie behavior,
 *   which is insertion-order-dependent and exactly the class of non-determinism {@link
 *   TriangleIdentitySizeAssignment}'s own doc warns about at length.
 *   <li>When a cell is popped: scan {@code relevantFaceIndices} for the LOWEST-scoring
 *   candidate that's {@link BiomeGraph#isAllowed} against every currently-decided Moore
 *   neighbor (orthogonal + diagonal — the 4 orthogonal neighbors alone miss a biome pair
 *   touching only at a single corner pixel). This is a single linear scan, not a
 *   precomputed per-cell sorted list — legality only ever excludes the plain search's
 *   true best candidate in the rare case a neighbor conflict actually exists, so there's
 *   no need to pay for a full ranking up front.
 *   <li><b>Fallback</b>, only when no relevant face is legal: scan the FULL graph (every
 *   biome the graph knows about, not just faces physically near this cell) for one legal
 *   against every currently-decided neighbor simultaneously. This subsumes "just copy a
 *   decided neighbor's own biome" as one case (always self-legal per {@code isAllowed}'s
 *   own "same biome always allowed next to itself" rule) without needing it as a separate
 *   mechanism. Tie-break: most currently-decided neighbors already matching that biome
 *   (descending), then {@link BiomeId}'s natural order (ascending) — deterministic, no
 *   silent "first one found" behavior.
 *   <li><b>Genuine dead end</b> (fallback also exhausted): throws. Real, not a bug — two
 *   already-decided Moore neighbors of the SAME cell (e.g. north and west) are never
 *   themselves adjacent to each other, so nothing ever legality-checks them against each
 *   other directly; it's possible for each to be individually fine against everything it
 *   was actually checked against while still being mutually incompatible with the other.
 *   No growth order can structurally prevent this on its own — only {@link
 *   BiomeGraph#requireEveryLocalNeighborhoodSolvable} proves it can't happen for a given
 *   graph, which {@code ModBiomeGraph.DEFAULT} calls. Failing loud here matches this
 *   codebase's own established, already-shipped production precedent (see {@code
 *   com.mineutze.worldgen.integration.ModOverworldBiomeSource#getNoiseBiome}, which
 *   already throws uncaught for a comparable "should be unreachable" case) — never a
 *   silent fallback to an illegal placement, which would violate the whole point of this
 *   class.
 * </ol>
 *
 * <p><b>Performance</b>: NOT embarrassingly parallel like the plain search — later cells'
 * legal candidates genuinely depend on earlier cells' committed decisions, so this must
 * run sequentially. A real, permanent, structural cost of the zero-tolerance guarantee,
 * not a temporary implementation detail — which is exactly why it's used ONLY for the
 * one final output, never inside {@code correctRadii}'s own repeated per-face,
 * per-pass measurement loop (that stays on the cheap plain search).
 */
final class LegalityConstrainedRasterizer {

    private LegalityConstrainedRasterizer() {
    }

    private record CellEntry(MacroCellPos cell, double[] warped, double bestScore) {
    }

    static Map<MacroCellPos, BiomeId> rasterize(
        List<MacroCellPos> cells, int cellSize, Set<Integer> relevantFaceIndices, FieldContext field,
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, BiomeGraph graph,
        Map<MacroCellPos, BiomeId> fixedBoundaryCells
    ) {
        List<BiomeId> allBiomesSorted = graph.nodes().stream().map(BiomeNode::id).sorted().toList();

        List<CellEntry> entries = new ArrayList<>(cells.size());
        for (MacroCellPos cell : cells) {
            double blockX = cell.x() * cellSize + cellSize / 2.0;
            double blockZ = cell.z() * cellSize + cellSize / 2.0;
            double[] warped = field.warp().warp(blockX, blockZ);

            double bestScore = Double.MAX_VALUE;
            for (int faceIndex : relevantFaceIndices) {
                double candidateScore = TriangleFieldGeometryRealization.score(warped, field, faceIndex);
                if (candidateScore < bestScore) {
                    bestScore = candidateScore;
                }
            }
            entries.add(new CellEntry(cell, warped, bestScore));
        }

        PriorityQueue<CellEntry> queue = new PriorityQueue<>(entries.size(), Comparator
            .comparingDouble(CellEntry::bestScore)
            .thenComparingInt((CellEntry e) -> e.cell().x())
            .thenComparingInt((CellEntry e) -> e.cell().z()));
        queue.addAll(entries);

        Map<MacroCellPos, BiomeId> decided = new HashMap<>(cells.size() * 2);
        while (!queue.isEmpty()) {
            CellEntry entry = queue.poll();
            MacroCellPos cell = entry.cell();
            List<BiomeId> decidedNeighbors = mooreNeighborBiomes(cell, decided, fixedBoundaryCells);

            BiomeId winner = null;
            double bestLegalScore = Double.MAX_VALUE;
            for (int faceIndex : relevantFaceIndices) {
                BiomeId candidate = assignments.get(faceIndex).biome();
                if (!legalAgainstAll(candidate, decidedNeighbors, graph)) {
                    continue;
                }
                double candidateScore = TriangleFieldGeometryRealization.score(entry.warped(), field, faceIndex);
                if (candidateScore < bestLegalScore) {
                    bestLegalScore = candidateScore;
                    winner = candidate;
                }
            }

            if (winner == null) {
                winner = fallbackBiome(decidedNeighbors, allBiomesSorted, graph);
            }
            if (winner == null) {
                throw new IllegalStateException(
                    "LegalityConstrainedRasterizer: no biome in the graph is legal against every already-decided "
                        + "Moore neighbor of " + cell + " (neighbor biomes: " + decidedNeighbors + ") - this is a "
                        + "genuine BiomeGraph configuration gap (see BiomeGraph.requireEveryLocalNeighborhoodSolvable, "
                        + "which should be run against this graph offline before it ships), not a code bug."
                );
            }
            decided.put(cell, winner);
        }
        return decided;
    }

    /** Every already-decided Moore (orthogonal + diagonal) neighbor's biome — checked against this region's own growing result first, then already-committed cross-region context. Undecided neighbors impose no constraint. */
    private static List<BiomeId> mooreNeighborBiomes(MacroCellPos cell, Map<MacroCellPos, BiomeId> decided, Map<MacroCellPos, BiomeId> fixedBoundaryCells) {
        List<BiomeId> neighbors = new ArrayList<>(8);
        for (MacroCellPos neighbor : Stream.concat(cell.orthogonalNeighbors().stream(), cell.diagonalNeighbors().stream()).toList()) {
            BiomeId biome = decided.get(neighbor);
            if (biome == null) {
                biome = fixedBoundaryCells.get(neighbor);
            }
            if (biome != null) {
                neighbors.add(biome);
            }
        }
        return neighbors;
    }

    private static boolean legalAgainstAll(BiomeId candidate, List<BiomeId> decidedNeighbors, BiomeGraph graph) {
        for (BiomeId neighborBiome : decidedNeighbors) {
            if (!graph.isAllowed(candidate, neighborBiome)) {
                return false;
            }
        }
        return true;
    }

    /** Full-graph scan, not scoped to relevantFaceIndices - only reached when nothing physically nearby is legal. Ranked by most currently-decided neighbors already matching (descending), then BiomeId's natural order (ascending) as the deterministic tie-break; allBiomesSorted is already ascending, and only a STRICTLY greater match count replaces the running best, so the first-encountered (lowest BiomeId) among ties wins. */
    private static BiomeId fallbackBiome(List<BiomeId> decidedNeighbors, List<BiomeId> allBiomesSorted, BiomeGraph graph) {
        BiomeId best = null;
        long bestMatchCount = -1;
        for (BiomeId candidate : allBiomesSorted) {
            if (!legalAgainstAll(candidate, decidedNeighbors, graph)) {
                continue;
            }
            long matchCount = decidedNeighbors.stream().filter(candidate::equals).count();
            if (matchCount > bestMatchCount) {
                bestMatchCount = matchCount;
                best = candidate;
            }
        }
        return best;
    }
}
