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
import java.util.stream.Stream;

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
 * #wouldPinchAnAlreadyDecidedNeighbor} (measured to cut pinch points ~22% with zero
 * visual regression) and {@link #fillGaps}, not by suppressing the ordering that gives
 * this pipeline its organic character. A fixed {@link MacroCellPos} tie-break comes last
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
 * <p>Once the main queue drains, any cell still unclaimed — growth left a gap, or a
 * face's whole quota went to cells elsewhere — is closed by {@link #fillGaps}, a small
 * fixed-point legality-driven cleanup pass with the same fail-loud dead-end handling the
 * old field stage's final rasterizer used (backstopped by {@link
 * BiomeGraph#requireEveryLocalNeighborhoodSolvable}), expected to be smaller and rarer
 * than before — every adjacency here corresponds to either a real CSP-checked triangle
 * adjacency or an already-known-legal cross-region cell, never an incidental
 * distance-competition overlap between two faces that were never checked against each
 * other.
 *
 * <p><b>Known open gap</b> (2026-08-23, measured on real worldSeed=42 production export):
 * small fragments and one-cell pinch points are both still well above the old field
 * stage's own numbers (39/4247) — currently 623/5561 with this mechanism, real progress
 * from the first working version (636/7129) via {@link #wouldPinchAnAlreadyDecidedNeighbor}
 * alone, but not yet closed. Zero illegal adjacencies holds throughout regardless. Next
 * candidate direction, not yet tried: strengthening cohesion/pinch rejection further
 * without reintroducing distance-first ordering's flattening effect.
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
            if (!legalAgainstAll(candidate, mooreNeighborBiomes(entry.cell(), decided), graph)) {
                continue;
            }
            // Bootstrap exemption: a patch that doesn't have AT LEAST minSameBiomeNeighbors
            // cells yet can never produce a candidate with that many same-biome Moore
            // neighbors in the first place (pigeonhole - a candidate can't be adjacent to
            // more already-claimed same-biome cells than exist in total), so cohesion is
            // unsatisfiable-by-construction, not a real quality signal, until the patch
            // reaches that size. A first version only exempted the literal first cell
            // (count == 0) - looked right, but measured wrong: for COMPACT's threshold of
            // 2, the SECOND cell is just as structurally impossible to pass (only 1
            // same-biome cell exists to be adjacent to), so every genuinely-new isolated
            // patch was still permanently stuck at exactly 1 cell - confirmed directly on a
            // real production export: 593 of 623 small fragments were size=1, evenly spread
            // across every biome, not concentrated anywhere - a systemic pattern, not
            // scattered bad luck. GrowthCohesionProfile's own doc describes a bootstrap
            // exemption existing but nothing in this codebase ever actually implemented it
            // (GrowthEngine.satisfiesCohesion itself has no such case).
            BiomeNode candidateNode = graph.node(candidate);
            int minSameBiomeNeighbors = candidateNode.cohesionProfile().minSameBiomeNeighbors();
            if (claimedCountByFace.get(faceIndex) >= minSameBiomeNeighbors
                && !GrowthEngine.satisfiesCohesion(entry.cell(), candidate, decided, candidateNode.cohesionProfile())) {
                continue;
            }
            // Anti-pinch: reject if committing HERE would retroactively turn an
            // already-decided orthogonal neighbor into a one-cell-wide pinch (both
            // opposite neighbors on one axis now a different biome, while still only
            // connected on the other axis). GrowthCohesionProfile's own
            // minSameBiomeNeighbors=2 (COMPACT) doesn't catch this - a straight 1-wide
            // strip naturally has exactly 2 same-biome Moore neighbors (its two ends),
            // which already clears a ">= 2" bar. A SOFT reject, not a hard block: this
            // just skips this one candidate for this cell - another already-queued
            // candidate for the same cell, or fillGaps as a last resort, still covers it,
            // so this can never turn a real dead end into a worse one.
            if (wouldPinchAnAlreadyDecidedNeighbor(entry.cell(), candidate, decided)) {
                continue;
            }

            decided.put(entry.cell(), candidate);
            claimedCountByFace.merge(faceIndex, 1, Integer::sum);

            for (MacroCellPos neighbor : mooreNeighbors(entry.cell())) {
                if (!regionCellSet.contains(neighbor) || decided.containsKey(neighbor)) {
                    continue;
                }
                // ANY assigned face whose biome is legal here inherits this shared seam
                // directly - the actual growth-from-neighbor mechanism, not a special case:
                // it's just what "offer my newly-claimed cell's neighbors to whoever can
                // legally use them" naturally produces. Deliberately NOT restricted to real
                // TriangleAdjacency mesh-neighbors of faceIndex - an earlier version was,
                // and measured to leave real dead zones: growth doesn't respect mesh
                // topology the way the CSP does, so two faces can end up geometrically
                // adjacent without ever being real mesh neighbors, and if neither's
                // TriangleAdjacency set covers the other, that patch of the map never gets
                // ANY candidate at all - a hole nothing else can ever discover. Grouped by
                // BIOME (offering only the single best-placed face of each legal biome, not
                // every one of its possibly-many faces) for the same reason - see
                // facesByBiome's own doc for the measured cost this avoids. This also
                // naturally covers "the SAME face keeps growing" - candidate's own biome is
                // included in this same scan (self is always legal next to itself).
                for (Map.Entry<BiomeId, List<Integer>> biomeFaces : facesByBiome.entrySet()) {
                    if (!graph.isAllowed(biomeFaces.getKey(), candidate)) {
                        continue;
                    }
                    int bestFace = bestFaceFor(biomeFaces.getValue(), neighbor, claimedCountByFace, targetCells, seedCellByFace);
                    queue.add(new CellEntry(neighbor, bestFace, quotaRatio(bestFace, claimedCountByFace, targetCells), neighbor.distanceSquaredTo(seedCellByFace.get(bestFace))));
                }
            }
        }

        fillGaps(cells, decided, graph, seedCellByFace, assignments);

        Map<MacroCellPos, BiomeId> result = new LinkedHashMap<>();
        for (MacroCellPos cell : cells) {
            result.put(cell, decided.get(cell));
        }
        return result;
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

    private static List<MacroCellPos> mooreNeighbors(MacroCellPos cell) {
        return Stream.concat(cell.orthogonalNeighbors().stream(), cell.diagonalNeighbors().stream()).toList();
    }

    private static List<BiomeId> mooreNeighborBiomes(MacroCellPos cell, Map<MacroCellPos, BiomeId> decided) {
        List<BiomeId> neighbors = new ArrayList<>(8);
        for (MacroCellPos neighbor : mooreNeighbors(cell)) {
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
     * Expected small and rare (see this class's own doc for why), unlike the old field
     * stage's fallback which had to cover the WHOLE final raster. Same fail-loud dead
     * end the old field stage's final rasterizer used, same tie-break (most matching
     * already-decided Moore neighbor, then {@link BiomeId} natural order) since nothing
     * here is tied to a specific face/quota any more — it's a pure legality fill.
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
                BiomeId winner = neighborBiomes.isEmpty() ? nearestFaceBiome(cell, seedCellByFace, assignments) : bestLegalFill(neighborBiomes, allBiomesSorted, graph);
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

    private static BiomeId bestLegalFill(List<BiomeId> decidedNeighbors, List<BiomeId> allBiomesSorted, BiomeGraph graph) {
        if (decidedNeighbors.isEmpty()) {
            return null; // no context to fill from yet - a later pass, once a neighbor fills, will reach it
        }
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
