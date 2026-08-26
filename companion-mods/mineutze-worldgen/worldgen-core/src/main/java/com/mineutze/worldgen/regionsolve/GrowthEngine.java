package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Legality and cohesion checks shared by every pixel-grid geometry/repair
 * pass, e.g. {@link PixelFragmentRepair}. A candidate biome is legal at a
 * cell if it's allowed, per the BiomeGraph, next to every already-assigned
 * neighbor; cohesive if it has enough already-assigned support around it,
 * per {@link #MIN_SAME_BIOME_NEIGHBORS}.
 */
public final class GrowthEngine {

    /**
     * How tightly a growing patch must hold together: a candidate cell needs at least
     * this many already-claimed same-biome neighbors, over the full 8-cell Moore
     * neighborhood (orthogonal + diagonal — a solid, convex blob's exterior boundary
     * cell only ever touches ONE already-claimed cell orthogonally, a fact of
     * 4-connectivity grids, not an edge case, so any threshold above 1 checked
     * orthogonal-only makes growth dead-end after the seed's ring no matter the shape —
     * discovered the hard way via the export CLI turning solid filler-only once this
     * was tried orthogonal-only). Diagonal-inclusive counting actually measures
     * thickness: a true 1-wide corridor cell still touches only 1 Moore-neighbor
     * (correctly blocked), a 2-wide strip touches 2 (correctly allowed).
     *
     * <p>One single global value, not a per-biome profile: a per-biome
     * shape-identity system (including a distinct, more elongated profile for
     * coastline-shaped biomes) was designed and tried, but removed before any
     * biome successfully used a different value from this one in production —
     * see git history if per-biome shape differentiation is worth resurrecting;
     * per explicit direction, deferred until a fully working proof of concept
     * exists that needs it.
     */
    static final int MIN_SAME_BIOME_NEIGHBORS = 2;

    private GrowthEngine() {
    }

    /**
     * Does {@code biome} have enough already-assigned support around {@code candidate}
     * to grow there, per {@link #MIN_SAME_BIOME_NEIGHBORS}. Independent of adjacency
     * legality ({@link #isLegal}), which is a separate concern against OTHER biomes;
     * this only looks at cells already equal to {@code biome} itself. Package-visible:
     * {@link SeamGrowthRasterizer} reuses this exact check during its own growth.
     */
    static boolean satisfiesCohesion(MacroCellPos candidate, BiomeId biome, Map<MacroCellPos, BiomeId> assignment) {
        long sameBiomeNeighbors = Stream.concat(candidate.orthogonalNeighbors().stream(), candidate.diagonalNeighbors().stream())
            .filter(neighbor -> biome.equals(assignment.get(neighbor))).count();
        return sameBiomeNeighbors >= MIN_SAME_BIOME_NEIGHBORS;
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
