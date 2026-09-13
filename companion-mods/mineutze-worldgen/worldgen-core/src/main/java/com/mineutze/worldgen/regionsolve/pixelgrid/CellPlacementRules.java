package com.mineutze.worldgen.regionsolve.pixelgrid;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Legality and cohesion checks shared by every pixel-grid geometry/repair pass, e.g.
 * {@link PixelFragmentRepair}. A candidate biome is legal at a cell if allowed, per the
 * BiomeGraph, next to every already-assigned neighbor; cohesive if it has enough
 * already-assigned support around it, per {@link #MIN_SAME_BIOME_NEIGHBORS}.
 *
 * <p><b>Export-only today, deliberately kept, not accidental dead code</b> - the shipped
 * mod's live path ({@link TriangleFieldGeometryRealization#realizeCluster}) never calls
 * this class or its real callers ({@link PatchSizeEnforcer}, {@link PixelFragmentRepair},
 * {@link SeparatorWidener}); all three only run via {@code RegionGridSolver}/{@code
 * TriangleFieldRegionExportCli}, the standalone visual/JSON export tooling. Confirmed
 * directly, not assumed - see project memory's 2026-08-24 status entry.
 */
public final class CellPlacementRules {

    /**
     * How tightly a growing patch must hold together: a candidate cell needs at least
     * this many already-claimed same-biome neighbors, over the full 8-cell Moore
     * neighborhood (orthogonal + diagonal - a solid, convex blob's exterior boundary
     * cell only ever touches ONE already-claimed cell orthogonally, a fact of
     * 4-connectivity grids, not an edge case, so any threshold above 1 checked
     * orthogonal-only makes growth dead-end after the seed's ring regardless of shape -
     * discovered the hard way via the export CLI turning solid filler-only once tried
     * orthogonal-only). Diagonal-inclusive counting actually measures thickness: a true
     * 1-wide corridor cell still touches only 1 Moore-neighbor (correctly blocked), a
     * 2-wide strip touches 2 (correctly allowed).
     *
     * <p>One single global value, not a per-biome profile: a per-biome shape-identity
     * system (including a distinct, more elongated profile for coastline-shaped biomes)
     * was designed and tried, but removed before any biome used a different value in
     * production - see git history if worth resurrecting; per explicit direction,
     * deferred until a working proof of concept needs it.
     */
    static final int MIN_SAME_BIOME_NEIGHBORS = 2;

    private CellPlacementRules() {
    }

    /**
     * Does {@code biome} have enough already-assigned support around {@code candidate}
     * to grow there, per {@link #MIN_SAME_BIOME_NEIGHBORS}. Independent of adjacency
     * legality ({@link #isLegal}), a separate concern against OTHER biomes; this only
     * looks at cells already equal to {@code biome} itself. Package-visible: {@link
     * PixelFragmentRepair} and {@link SeparatorWidener} reuse this exact check
     * (export-only - see this class's doc above).
     */
    static boolean satisfiesCohesion(MacroCellPos candidate, BiomeId biome, Map<MacroCellPos, BiomeId> assignment) {
        long sameBiomeNeighbors = Stream.concat(candidate.orthogonalNeighbors().stream(), candidate.diagonalNeighbors().stream())
            .filter(neighbor -> biome.equals(assignment.get(neighbor))).count();
        return sameBiomeNeighbors >= MIN_SAME_BIOME_NEIGHBORS;
    }

    /** Legal (Moore) for one candidate cell, excluding any neighbor inside {@code excludeFromNeighbors} - an already-claimed working set the caller is still assembling, or {@code Set.of()} if none. */
    static boolean isLegalForCell(
        MacroCellPos cell, Set<MacroCellPos> excludeFromNeighbors, BiomeId candidate,
        Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph
    ) {
        return isLegal(Set.of(cell), excludeFromNeighbors, candidate, assignment, graph);
    }

    /** Legal (Moore) for a whole group checking itself as one unit — a neighbor inside the group's own interior is never counted against it. */
    static boolean isLegalForGroup(Set<MacroCellPos> group, BiomeId candidate, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        return isLegal(group, group, candidate, assignment, graph);
    }

    /**
     * The one shared implementation behind {@link #isLegalForCell} and {@link
     * #isLegalForGroup} - this check used to be reimplemented independently in several
     * places across the repair pipeline (single-cell and whole-group variants), two of
     * which quietly checked ORTHOGONAL neighbors only, silently missing a real diagonal
     * illegal-adjacency case this pipeline is supposed to guarantee against.
     * Consolidating removes that possibility, rather than patching those two spots.
     */
    private static boolean isLegal(
        Set<MacroCellPos> cellsToCheck, Set<MacroCellPos> excludeFromNeighbors, BiomeId candidate,
        Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph
    ) {
        for (MacroCellPos cell : cellsToCheck) {
            for (MacroCellPos neighbor : cell.mooreNeighbors()) {
                if (excludeFromNeighbors.contains(neighbor)) {
                    continue;
                }
                BiomeId neighborBiome = assignment.get(neighbor);
                // Same biome touching itself is always fine - same patch's own interior, never a
                // violation regardless of graph.isAllowed(biome, biome), no longer trivially true
                // now self-adjacency isn't a free pass (see BiomeGraph.isAllowed's doc). This
                // class's growth model still needs same-biome cells to merge.
                if (neighborBiome != null && !candidate.equals(neighborBiome) && !graph.isAllowed(candidate, neighborBiome)) {
                    return false;
                }
            }
        }
        return true;
    }
}
