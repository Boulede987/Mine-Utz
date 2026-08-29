package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment;

import java.util.List;
import java.util.Map;

/**
 * Fixed-point cleanup for cells {@link SeamGrowthRasterizer}'s main growth pass left
 * unclaimed — a real gap between target-bounded faces, or a face whose quota went to
 * cells elsewhere. Expected small and rare, unlike the old field stage's fallback which
 * had to cover the WHOLE final raster: every adjacency here corresponds to either a real
 * CSP-checked triangle adjacency or an already-known-legal cross-region cell, never an
 * incidental distance-competition overlap between two faces that were never checked
 * against each other. Same fail-loud dead end the old field stage's final rasterizer
 * used (backstopped by {@link BiomeGraph#requireEveryLocalNeighborhoodSolvable}), same
 * tie-break (most matching already-decided Moore neighbor, then {@link BiomeId} natural
 * order) since nothing here is tied to a specific face/quota any more — it's a pure
 * legality fill. Shares {@link SeamGrowthRasterizer}'s own legality/pinch primitives
 * ({@code mooreNeighborBiomes}, {@code legalAgainstAll}, {@code
 * wouldPinchAnAlreadyDecidedNeighbor}) rather than duplicating them — both classes must
 * agree on what "legal" and "pinched" mean.
 */
final class GrowthGapFiller {

    private GrowthGapFiller() {
    }

    /**
     * A cell with ZERO decided Moore neighbors anywhere — confirmed real, not
     * theoretical: {@link MacroGridConfig}'s own region-boundary warp classifies cell
     * membership independently per cell, which can leave a handful of cells genuinely
     * disconnected (Moore-wise) from the rest of the region's own cell set, something
     * the old field stage's pure per-cell distance search never cared about but pure
     * growth structurally can't reach through adjacency alone — bootstraps to the
     * nearest assigned face's biome, same idea as that face's own first cell, vacuously
     * legal since there's nothing nearby yet to conflict with.
     */
    static void fillGaps(
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
                List<BiomeId> neighborBiomes = SeamGrowthRasterizer.mooreNeighborBiomes(cell, decided);
                BiomeId winner = neighborBiomes.isEmpty()
                    ? nearestFaceBiome(cell, seedCellByFace, assignments)
                    : bestLegalFill(cell, neighborBiomes, allBiomesSorted, graph, decided);
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
                        + "neighbor of " + cell + " (neighbor biomes: " + SeamGrowthRasterizer.mooreNeighborBiomes(cell, decided) + ") - this is a "
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
            if (!SeamGrowthRasterizer.legalAgainstAll(candidate, decidedNeighbors, graph)) {
                continue;
            }
            int pinchScore = (SeamGrowthRasterizer.wouldPinchAnAlreadyDecidedNeighbor(cell, candidate, decided) ? 0 : 1)
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
     * SeamGrowthRasterizer#wouldPinchAnAlreadyDecidedNeighbor} already documents for the
     * retroactive case.
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
