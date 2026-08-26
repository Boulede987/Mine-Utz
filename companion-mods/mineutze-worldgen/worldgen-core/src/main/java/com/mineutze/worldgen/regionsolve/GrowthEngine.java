package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Legality and cohesion checks shared by every pixel-grid geometry/repair
 * pass, e.g. {@link PixelFragmentRepair}. A candidate biome is legal at a
 * cell if it's allowed, per the BiomeGraph, next to every already-assigned
 * neighbor; cohesive if it has enough already-assigned support around it
 * per its own {@link GrowthCohesionProfile}.
 */
public final class GrowthEngine {

    private GrowthEngine() {
    }

    /**
     * A biome's shape identity (see {@link GrowthCohesionProfile}'s own doc) — does
     * {@code biome} have enough already-assigned support around {@code candidate} to
     * grow there. Independent of adjacency legality ({@link #isLegal}), which is a
     * separate concern against OTHER biomes; this only looks at cells already equal
     * to {@code biome} itself. Package-visible: {@link SeamGrowthRasterizer} reuses
     * this exact check during its own growth.
     *
     * The same-biome neighbor count is over the full 8-cell Moore neighborhood
     * (orthogonal + diagonal), not just the 4 orthogonal cells — a solid, convex
     * blob's exterior boundary cell only ever touches ONE already-claimed cell
     * orthogonally (a fact of 4-connectivity grids, not an edge case), so any
     * {@code minSameBiomeNeighbors} above 1 checked orthogonal-only makes growth
     * dead-end after the seed's ring no matter the shape — discovered the hard way
     * via the export CLI turning solid filler-only once a 2-neighbor profile was
     * tried. Diagonal-inclusive counting actually measures thickness: a true
     * 1-wide corridor cell still touches only 1 Moore-neighbor (correctly
     * blocked), a 2-wide strip touches 2 (correctly allowed).
     */
    static boolean satisfiesCohesion(
        MacroCellPos candidate, BiomeId biome, Map<MacroCellPos, BiomeId> assignment, GrowthCohesionProfile cohesion
    ) {
        long sameBiomeNeighbors = Stream.concat(candidate.orthogonalNeighbors().stream(), candidate.diagonalNeighbors().stream())
            .filter(neighbor -> biome.equals(assignment.get(neighbor))).count();
        return sameBiomeNeighbors >= cohesion.minSameBiomeNeighbors();
    }

    /**
     * Candidate is legal at this cell if it's allowed next to every
     * already-assigned neighbor outside the growing patch.
     */
    static boolean isLegal(
        MacroCellPos cell,
        BiomeId candidate,
        Map<MacroCellPos, BiomeId> externallyAssigned,
        BiomeGraph graph
    ) {
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            BiomeId neighborBiome = externallyAssigned.get(neighbor);
            if (neighborBiome != null && !graph.isAllowed(candidate, neighborBiome)) {
                return false;
            }
        }
        return true;
    }
}
