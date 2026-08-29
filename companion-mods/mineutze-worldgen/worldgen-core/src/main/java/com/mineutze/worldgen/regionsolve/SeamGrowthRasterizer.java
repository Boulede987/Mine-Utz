package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.topology.Point2D;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Replaces the old distance-minus-radius Voronoi competition (plus its warp/domain-warp
 * machinery) with GROWTH: the triangle mesh is only ever consulted for two things after
 * the CSP has run — each face's target size, and (for the bootstrap case only) roughly
 * where it starts. A biome's actual pixel SHAPE is never a raw geometric query against
 * mesh point positions; it's grown outward from whatever its already-decided neighbors
 * (this region's own already-grown faces, AND cross-region {@code fixedBoundaryCells})
 * leave behind, until it reaches its own target cell count. The very first cell ever
 * claimed (and any face that genuinely has no reachable already-decided neighbor yet)
 * bootstraps from its own triangle centroid — the one place raw mesh position still
 * matters.
 *
 * <p>Real root-cause fix, not a tuning pass: the old field stage's "thin bridge biome"
 * artifact (a biome legal to two otherwise-mutually-illegal neighbors getting squeezed
 * into an emergency 1-cell legality patch by the final rasterizer) happened because a
 * free triangle's DRAWN territory could reach a region seam its CENTROID was never
 * legality-checked against. Here, nothing is ever claimed without a live Moore-neighbor
 * legality check against whatever's ALREADY decided, cross-region or not — there's no
 * "drawn reach" beyond what growth has actually, legally claimed cell by cell. A bridge
 * biome required between two illegal neighbors is a real CSP-assigned face with its own
 * real target size, since the CSP already refuses to let two illegal biomes become
 * Delaunay-adjacent triangles in the first place — it grows a real patch here, not a
 * last-resort sliver.
 *
 * <p><b>Mechanism</b>: one global priority queue of (cell, owning face) candidates,
 * ordered by the owning face's current quota deficit FIRST (most-behind-target wins),
 * then by distance from that face's own triangle centroid as a tie-break. Distance-first
 * was tried instead (kept growth strictly ring-by-ring, no face able to jump ahead of its
 * own frontier) and measured to fully eliminate the "sniping" fragmentation failure mode
 * below — but it also flattened the result into large, geometrically rigid single-biome
 * regions with visibly straight boundaries, a direct, measured, user-rejected regression
 * from the varied, organic multi-patch texture quota-first produces. Quota-first's real
 * failure mode — a badly-behind-quota face winning a legal cell right at another biome's
 * ADVANCING FRONTIER (not a random teleport; always still a real Moore-neighbor of
 * something already decided), which can end up isolated if the surrounding area fills in
 * before that face's other territory connects to it — is handled instead by {@link
 * #wouldPinchAnAlreadyDecidedNeighbor} (measured to meaningfully cut pinch points with
 * zero visual regression) and {@link #fillGaps}, not by suppressing the ordering that
 * gives this pipeline its organic character. A fixed {@link MacroCellPos} tie-break comes last
 * (never queue insertion order, matching this codebase's established determinism
 * discipline). Every face's own triangle centroid is seeded as its bootstrap candidate up
 * front; real inheritance happens automatically once ANY cell is claimed — its Moore
 * neighbors are offered both to the claiming face itself (continue growing) AND to EVERY
 * OTHER assigned face whose biome is legal there (a literal shared-seam inheritance, not
 * a separate mechanism). Deliberately NOT scoped to a face's real triangle-mesh
 * neighbors — a first version was, and measured to leave real dead zones (growth doesn't
 * respect mesh topology the way the CSP does; two faces can end up geometrically adjacent
 * without ever being real mesh neighbors). Priority is a snapshot taken at push time, a
 * heuristic ordering, not a guarantee, and cheap to keep that way.
 *
 * <p>Once the main queue drains, any cell still unclaimed is closed by {@link #fillGaps}
 * — see that method's own doc for how and why.
 *
 * <p>Small-fragment and one-cell-pinch counts are a real, ongoing quality measure, not a
 * guarantee — unlike illegal adjacencies (always zero, see above), a face's actual shape
 * can still end up fragmented or pinched by genuine local competition or a tight
 * local-legality pocket, neither of which is a bug in this class. See {@link
 * RegionIntegrityValidator} for how these are measured on a real export, and this class's
 * own git history for the sequence of root-cause fixes applied so far (each commit
 * message states its own before/after numbers there, not duplicated here to go stale the
 * next time this class changes).
 */
final class SeamGrowthRasterizer {

    private SeamGrowthRasterizer() {
    }

    private record CellEntry(MacroCellPos cell, int faceIndex, double quotaRatio, long distanceSquared) {
    }

    static Map<MacroCellPos, BiomeId> rasterize(
        List<MacroCellPos> cells, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments,
        Map<Integer, Point2D> centroids, BiomeGraph graph,
        Map<MacroCellPos, BiomeId> fixedBoundaryCells, MacroGridConfig gridConfig
    ) {
        Set<MacroCellPos> regionCellSet = new HashSet<>(cells);
        // Unified "already decided" view for BOTH this region's own growing result AND
        // cross-region context - fixedBoundaryCells' keys never coincide with regionCellSet
        // (RegionBoundaryCollector only ever sources it from a NEIGHBOR region's own solved
        // cells, and every MacroCellPos belongs to exactly one region), so one shared map
        // safely serves every legality/cohesion lookup with no merge-view cost per check.
        Map<MacroCellPos, BiomeId> decided = new HashMap<>(fixedBoundaryCells);

        Map<Integer, Integer> targetCells = new HashMap<>();
        Map<Integer, Integer> claimedCountByFace = new HashMap<>();
        Map<Integer, MacroCellPos> seedCellByFace = new HashMap<>();
        // Faces grouped by biome - a region-wide claim-time scan over every DISTINCT
        // biome (a handful, e.g. 6 in ModBiomeGraph.DEFAULT) instead of every FACE (can be
        // 100+, since one biome routinely has several separate patches) is what keeps the
        // "offer to any other legal biome" step (below) affordable - measured directly:
        // scanning all assignments.keySet() per claim made the full test suite ~4x slower.
        Map<BiomeId, List<Integer>> facesByBiome = new HashMap<>();
        for (Map.Entry<Integer, TriangleIdentitySizeAssignment.Assignment> entry : assignments.entrySet()) {
            int faceIndex = entry.getKey();
            targetCells.put(faceIndex, gridConfig.cellCountForWidthBlocks(entry.getValue().widthBlocks()));
            claimedCountByFace.put(faceIndex, 0);
            Point2D centroid = centroids.get(faceIndex);
            seedCellByFace.put(faceIndex, gridConfig.cellContaining(centroid.x(), centroid.z()));
            facesByBiome.computeIfAbsent(entry.getValue().biome(), key -> new ArrayList<>()).add(faceIndex);
        }

        PriorityQueue<CellEntry> queue = new PriorityQueue<>(cells.size(), Comparator
            .comparingDouble(CellEntry::quotaRatio)
            .thenComparingLong(CellEntry::distanceSquared)
            .thenComparingInt((CellEntry e) -> e.cell().x())
            .thenComparingInt((CellEntry e) -> e.cell().z()));

        reserveHeadStarts(assignments, seedCellByFace, regionCellSet, decided, claimedCountByFace, graph, facesByBiome, targetCells, queue);

        for (int faceIndex : assignments.keySet().stream().sorted().toList()) {
            MacroCellPos seed = seedCellByFace.get(faceIndex);
            if (regionCellSet.contains(seed) && !decided.containsKey(seed)) {
                queue.add(new CellEntry(seed, faceIndex, 0.0, 0L));
            }
        }

        while (!queue.isEmpty()) {
            CellEntry entry = queue.poll();
            if (decided.containsKey(entry.cell())) {
                continue; // stale - some other entry already claimed this cell
            }
            int faceIndex = entry.faceIndex();
            // Quota is the PRIMARY ordering signal, never a hard cap on eligibility - a
            // face past its own target still gets to claim a cell if nothing else legally
            // wants it, so growth always reaches every cell in the region (soft target,
            // hard no-gaps requirement - see this class's own doc).
            BiomeId candidate = assignments.get(faceIndex).biome();
            if (!isClaimable(entry.cell(), candidate, faceIndex, decided, claimedCountByFace, graph)) {
                continue;
            }

            decided.put(entry.cell(), candidate);
            claimedCountByFace.merge(faceIndex, 1, Integer::sum);
            offerNeighbors(entry.cell(), candidate, faceIndex, decided, regionCellSet, facesByBiome, claimedCountByFace, targetCells, seedCellByFace, graph, queue);
        }

        fillGaps(cells, decided, graph, seedCellByFace, assignments);

        Map<MacroCellPos, BiomeId> result = new LinkedHashMap<>();
        for (MacroCellPos cell : cells) {
            result.put(cell, decided.get(cell));
        }
        return result;
    }

    /**
     * Root-cause fix, not a repair pass over decided output: without this, every face's
     * first cell sits in the same top priority tier as every other face's first cell
     * (ratio exactly 0.0, distance exactly 0), so who gets a second cell is decided by
     * whichever face's neighbor offers happen to get processed first in the shared queue
     * — measured directly that 95% of the resulting size=1 fragments were pure geographic
     * bad luck (all 8 Moore neighbors claimed by other, perfectly LEGAL, faces before this
     * face's own continuation was ever considered), not blocked by any real illegality.
     */
    private static void reserveHeadStarts(
        Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments, Map<Integer, MacroCellPos> seedCellByFace,
        Set<MacroCellPos> regionCellSet, Map<MacroCellPos, BiomeId> decided, Map<Integer, Integer> claimedCountByFace,
        BiomeGraph graph, Map<BiomeId, List<Integer>> facesByBiome, Map<Integer, Integer> targetCells, PriorityQueue<CellEntry> queue
    ) {
        for (int faceIndex : assignments.keySet().stream().sorted().toList()) {
            MacroCellPos seed = seedCellByFace.get(faceIndex);
            if (!regionCellSet.contains(seed) || decided.containsKey(seed)) {
                continue;
            }
            BiomeId biome = assignments.get(faceIndex).biome();
            if (!isClaimable(seed, biome, faceIndex, decided, claimedCountByFace, graph)) {
                continue; // leave it to the main queue below - same outcome as before this pass existed
            }
            decided.put(seed, biome);
            claimedCountByFace.merge(faceIndex, 1, Integer::sum);
            List<MacroCellPos> claimedByThisFace = new ArrayList<>(List.of(seed));

            int headStartTarget = Math.max(1, CellPlacementRules.MIN_SAME_BIOME_NEIGHBORS);
            while (claimedByThisFace.size() < headStartTarget) {
                MacroCellPos next = pickHeadStartCandidate(claimedByThisFace, biome, faceIndex, decided, regionCellSet, claimedCountByFace, graph, seed);
                if (next == null) {
                    break; // no legal room to extend further right now - main queue still gets a shot at it later
                }
                decided.put(next, biome);
                claimedCountByFace.merge(faceIndex, 1, Integer::sum);
                claimedByThisFace.add(next);
            }

            for (MacroCellPos claimed : claimedByThisFace) {
                offerNeighbors(claimed, biome, faceIndex, decided, regionCellSet, facesByBiome, claimedCountByFace, targetCells, seedCellByFace, graph, queue);
            }
        }
    }

    /**
     * The three commit-time rules a candidate cell must pass, shared verbatim by the
     * head-start reservation pass and the main queue loop so both ever apply exactly the
     * same standard: legal against every already-decided Moore neighbor; satisfies
     * {@link CellPlacementRules#MIN_SAME_BIOME_NEIGHBORS} once past the bootstrap exemption
     * window (pigeonhole - a patch below that count can never satisfy cohesion in the
     * first place, so it isn't a real quality signal yet); and doesn't retroactively pinch
     * an already-decided orthogonal neighbor.
     */
    private static boolean isClaimable(
        MacroCellPos cell, BiomeId candidate, int faceIndex,
        Map<MacroCellPos, BiomeId> decided, Map<Integer, Integer> claimedCountByFace, BiomeGraph graph
    ) {
        if (!legalAgainstAll(candidate, mooreNeighborBiomes(cell, decided), graph)) {
            return false;
        }
        if (claimedCountByFace.get(faceIndex) >= CellPlacementRules.MIN_SAME_BIOME_NEIGHBORS
            && !CellPlacementRules.satisfiesCohesion(cell, candidate, decided)) {
            return false;
        }
        return !wouldPinchAnAlreadyDecidedNeighbor(cell, candidate, decided);
    }

    /**
     * Offers every Moore-neighbor of a just-claimed cell to whoever can legally use it -
     * the claiming face itself (guaranteed direct continuation candidate) AND, grouped by
     * biome, the single best-placed OTHER face of each other legal biome. Both pushes are
     * required, not just one: an earlier version pushed ONLY the claiming face for its own
     * biome (skipping the region-wide scan there), measured WORSE (455 → 591 fragments) -
     * the scan's "wrong" sibling pick isn't pure waste, it's what lets two separately
     * CSP-assigned faces of the SAME biome merge into one connected component when a
     * sibling's stolen cell lands adjacent to this one (fragment detection groups by
     * biome VALUE, not by which face nominally claimed a cell — see {@link
     * ConnectedComponents}). Keeping both gets the claiming face a guaranteed
     * continuation without losing that merging benefit.
     */
    private static void offerNeighbors(
        MacroCellPos claimedCell, BiomeId candidate, int faceIndex,
        Map<MacroCellPos, BiomeId> decided, Set<MacroCellPos> regionCellSet, Map<BiomeId, List<Integer>> facesByBiome,
        Map<Integer, Integer> claimedCountByFace, Map<Integer, Integer> targetCells, Map<Integer, MacroCellPos> seedCellByFace,
        BiomeGraph graph, PriorityQueue<CellEntry> queue
    ) {
        for (MacroCellPos neighbor : claimedCell.mooreNeighbors()) {
            if (!regionCellSet.contains(neighbor) || decided.containsKey(neighbor)) {
                continue;
            }
            queue.add(new CellEntry(neighbor, faceIndex, quotaRatio(faceIndex, claimedCountByFace, targetCells), neighbor.distanceSquaredTo(seedCellByFace.get(faceIndex))));
            for (Map.Entry<BiomeId, List<Integer>> biomeFaces : facesByBiome.entrySet()) {
                if (!graph.isAllowed(biomeFaces.getKey(), candidate)) {
                    continue;
                }
                int bestFace = bestFaceFor(biomeFaces.getValue(), neighbor, claimedCountByFace, targetCells, seedCellByFace);
                queue.add(new CellEntry(neighbor, bestFace, quotaRatio(bestFace, claimedCountByFace, targetCells), neighbor.distanceSquaredTo(seedCellByFace.get(bestFace))));
            }
        }
    }

    /**
     * The single best next cell for a face's own head-start reservation: a Moore-neighbor
     * of any cell this face already owns, not already decided by anyone, passing {@link
     * #isClaimable}, nearest to the face's own seed (then a fixed x/z tie-break) - the
     * same "closest to home" preference {@link #bestFaceFor} uses, just scoped to one
     * face's own frontier instead of choosing between faces.
     */
    private static MacroCellPos pickHeadStartCandidate(
        List<MacroCellPos> claimedByThisFace, BiomeId biome, int faceIndex,
        Map<MacroCellPos, BiomeId> decided, Set<MacroCellPos> regionCellSet, Map<Integer, Integer> claimedCountByFace,
        BiomeGraph graph, MacroCellPos seed
    ) {
        MacroCellPos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (MacroCellPos owned : claimedByThisFace) {
            for (MacroCellPos neighbor : owned.mooreNeighbors()) {
                if (!regionCellSet.contains(neighbor) || decided.containsKey(neighbor)) {
                    continue;
                }
                if (!isClaimable(neighbor, biome, faceIndex, decided, claimedCountByFace, graph)) {
                    continue;
                }
                long distance = neighbor.distanceSquaredTo(seed);
                if (best == null || distance < bestDistance
                    || (distance == bestDistance && (neighbor.x() < best.x() || (neighbor.x() == best.x() && neighbor.z() < best.z())))) {
                    best = neighbor;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** Lowest quota-deficit ratio first, then nearest own-seed distance to {@code cell} as a tie-break - same priority rule the main queue itself sorts by, just narrowed to one biome's own (usually small) face list instead of a full priority-queue push per face. */
    private static int bestFaceFor(List<Integer> facesOfBiome, MacroCellPos cell, Map<Integer, Integer> claimedCountByFace, Map<Integer, Integer> targetCells, Map<Integer, MacroCellPos> seedCellByFace) {
        int best = facesOfBiome.get(0);
        double bestRatio = quotaRatio(best, claimedCountByFace, targetCells);
        long bestDistance = cell.distanceSquaredTo(seedCellByFace.get(best));
        for (int i = 1; i < facesOfBiome.size(); i++) {
            int face = facesOfBiome.get(i);
            double ratio = quotaRatio(face, claimedCountByFace, targetCells);
            long distance = cell.distanceSquaredTo(seedCellByFace.get(face));
            if (ratio < bestRatio || (ratio == bestRatio && distance < bestDistance)) {
                best = face;
                bestRatio = ratio;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double quotaRatio(int faceIndex, Map<Integer, Integer> claimedCountByFace, Map<Integer, Integer> targetCells) {
        return (double) claimedCountByFace.get(faceIndex) / targetCells.get(faceIndex);
    }

    private static List<BiomeId> mooreNeighborBiomes(MacroCellPos cell, Map<MacroCellPos, BiomeId> decided) {
        List<BiomeId> neighbors = new ArrayList<>(8);
        for (MacroCellPos neighbor : cell.mooreNeighbors()) {
            BiomeId biome = decided.get(neighbor);
            if (biome != null) {
                neighbors.add(biome);
            }
        }
        return neighbors;
    }

    /**
     * True if setting {@code cell} to {@code candidate} would COMPLETE a one-cell-wide
     * pinch at an already-decided orthogonal neighbor: that neighbor's two opposite
     * cells on the ONE axis {@code cell} actually sits on (north+south, or east+west)
     * both a different biome than it, matching {@link RegionIntegrityValidator}'s own
     * pinch definition. Only fires when BOTH sides of that axis are already decided and
     * different — conservative on purpose: this claim can't change the neighbor's OTHER
     * (perpendicular) axis at all, and an undecided opposite side might still end up
     * same-biome once growth reaches it, so guessing either way there would risk
     * blocking otherwise-fine claims for no benefit.
     */
    private static boolean wouldPinchAnAlreadyDecidedNeighbor(MacroCellPos cell, BiomeId candidate, Map<MacroCellPos, BiomeId> decided) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            BiomeId neighborBiome = decided.get(neighbor);
            if (neighborBiome == null || neighborBiome.equals(candidate)) {
                continue; // undecided, or same biome - this claim can't newly pinch this neighbor along this axis
            }
            int dx = cell.x() - neighbor.x();
            int dz = cell.z() - neighbor.z();
            MacroCellPos oppositeSide = new MacroCellPos(neighbor.x() - dx, neighbor.z() - dz);
            BiomeId oppositeBiome = decided.get(oppositeSide);
            if (oppositeBiome != null && !neighborBiome.equals(oppositeBiome)) {
                return true; // both sides of this axis now decided and different from neighborBiome - a confirmed pinch
            }
        }
        return false;
    }

    private static boolean legalAgainstAll(BiomeId candidate, List<BiomeId> neighborBiomes, BiomeGraph graph) {
        for (BiomeId neighborBiome : neighborBiomes) {
            if (!graph.isAllowed(candidate, neighborBiome)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fixed-point cleanup for cells the main growth pass left unclaimed — a real gap
     * between target-bounded faces, or a face whose quota went to cells elsewhere.
     * Expected small and rare, unlike the old field stage's fallback which had to cover
     * the WHOLE final raster: every adjacency here corresponds to either a real
     * CSP-checked triangle adjacency or an already-known-legal cross-region cell, never
     * an incidental distance-competition overlap between two faces that were never
     * checked against each other. Same fail-loud dead end the old field stage's final
     * rasterizer used (backstopped by {@link BiomeGraph#requireEveryLocalNeighborhoodSolvable}),
     * same tie-break (most matching already-decided Moore neighbor, then {@link BiomeId}
     * natural order) since nothing here is tied to a specific face/quota any more — it's
     * a pure legality fill.
     *
     * <p>A cell with ZERO decided Moore neighbors anywhere — confirmed real, not
     * theoretical: {@link MacroGridConfig}'s own region-boundary warp classifies cell
     * membership independently per cell, which can leave a handful of cells genuinely
     * disconnected (Moore-wise) from the rest of the region's own cell set, something
     * the old field stage's pure per-cell distance search never cared about but pure
     * growth structurally can't reach through adjacency alone — bootstraps to the
     * nearest assigned face's biome, same idea as that face's own first cell, vacuously
     * legal since there's nothing nearby yet to conflict with.
     */
    private static void fillGaps(
        List<MacroCellPos> cells, Map<MacroCellPos, BiomeId> decided, BiomeGraph graph,
        Map<Integer, MacroCellPos> seedCellByFace, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments
    ) {
        List<BiomeId> allBiomesSorted = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        boolean anyChanged = true;
        while (anyChanged) {
            anyChanged = false;
            for (MacroCellPos cell : cells) {
                if (decided.containsKey(cell)) {
                    continue;
                }
                List<BiomeId> neighborBiomes = mooreNeighborBiomes(cell, decided);
                BiomeId winner = neighborBiomes.isEmpty() ? nearestFaceBiome(cell, seedCellByFace, assignments) : bestLegalFill(cell, neighborBiomes, allBiomesSorted, graph, decided);
                if (winner != null) {
                    decided.put(cell, winner);
                    anyChanged = true;
                }
            }
        }

        for (MacroCellPos cell : cells) {
            if (!decided.containsKey(cell)) {
                throw new IllegalStateException(
                    "SeamGrowthRasterizer: no biome in the graph is legal against every already-decided Moore "
                        + "neighbor of " + cell + " (neighbor biomes: " + mooreNeighborBiomes(cell, decided) + ") - this is a "
                        + "genuine BiomeGraph configuration gap (see BiomeGraph.requireEveryLocalNeighborhoodSolvable, "
                        + "which should be run against this graph offline before it ships), not a code bug."
                );
            }
        }
    }

    /** Deterministic nearest-by-face-seed-distance, then {@link BiomeId} natural order tie-break - only ever used for a cell with zero decided neighbors, so any assigned biome is vacuously legal there. */
    private static BiomeId nearestFaceBiome(MacroCellPos cell, Map<Integer, MacroCellPos> seedCellByFace, Map<Integer, TriangleIdentitySizeAssignment.Assignment> assignments) {
        BiomeId best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int faceIndex : assignments.keySet().stream().sorted().toList()) {
            long distance = cell.distanceSquaredTo(seedCellByFace.get(faceIndex));
            BiomeId candidate = assignments.get(faceIndex).biome();
            if (distance < bestDistance || (distance == bestDistance && (best == null || candidate.compareTo(best) < 0))) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Prefers a candidate that avoids creating a pinch over one that doesn't, THEN by
     * most-matching-neighbor count (the original tie-break). Real fix, not a tuning pass:
     * measured that the large majority of remaining pinch points trace back to a {@code
     * fillGaps} decision — a rare fallback (well under 1% of all cells) with previously
     * zero pinch-awareness, unlike the main growth loop which already avoids retroactively
     * pinching a neighbor (see git history for the measurement). Soft preference, not a
     * hard block: if EVERY legal candidate would pinch something, the original
     * match-count winner is still picked — this can only ever reduce pinches, never turn a
     * legal fill into an illegal one.
     */
    private static BiomeId bestLegalFill(
        MacroCellPos cell, List<BiomeId> decidedNeighbors, List<BiomeId> allBiomesSorted, BiomeGraph graph, Map<MacroCellPos, BiomeId> decided
    ) {
        if (decidedNeighbors.isEmpty()) {
            return null; // no context to fill from yet - a later pass, once a neighbor fills, will reach it
        }
        BiomeId best = null;
        int bestPinchScore = -1;
        long bestMatchCount = -1;
        for (BiomeId candidate : allBiomesSorted) {
            if (!legalAgainstAll(candidate, decidedNeighbors, graph)) {
                continue;
            }
            int pinchScore = (wouldPinchAnAlreadyDecidedNeighbor(cell, candidate, decided) ? 0 : 1)
                + (wouldBeSelfPinched(cell, candidate, decided) ? 0 : 1);
            long matchCount = decidedNeighbors.stream().filter(candidate::equals).count();
            if (pinchScore > bestPinchScore || (pinchScore == bestPinchScore && matchCount > bestMatchCount)) {
                bestPinchScore = pinchScore;
                bestMatchCount = matchCount;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Same pinch definition {@link RegionIntegrityValidator} itself measures with (missing
     * counts as different, a same-biome diagonal neighbor rescues it) - deliberately
     * independent of that class rather than a shared call, same precedent as {@link
     * #wouldPinchAnAlreadyDecidedNeighbor} already documents for the retroactive case.
     */
    private static boolean wouldBeSelfPinched(MacroCellPos cell, BiomeId candidate, Map<MacroCellPos, BiomeId> decided) {
        for (int dx : new int[] {-1, 1}) {
            for (int dz : new int[] {-1, 1}) {
                if (candidate.equals(decided.get(new MacroCellPos(cell.x() + dx, cell.z() + dz)))) {
                    return false;
                }
            }
        }
        BiomeId north = decided.get(new MacroCellPos(cell.x(), cell.z() - 1));
        BiomeId south = decided.get(new MacroCellPos(cell.x(), cell.z() + 1));
        if (!candidate.equals(north) && !candidate.equals(south)) {
            return true;
        }
        BiomeId east = decided.get(new MacroCellPos(cell.x() + 1, cell.z()));
        BiomeId west = decided.get(new MacroCellPos(cell.x() - 1, cell.z()));
        return !candidate.equals(east) && !candidate.equals(west);
    }
}
