package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;

/**
 * @param fixedBoundaryCells edge cells from whichever neighbor regions are
 *                           already solved (any of the 4 directions —
 *                           direction-agnostic, see doc/ARCHITECTURE.md),
 *                           fixed and never claimed by this solve
 * @param seedSpacingBlocks  placeholder, tune from logged solve output like
 *                           the macro-cell size (doc/ARCHITECTURE.md)
 * @param backtrackDepthCap  sanity limit only; the universal-filler
 *                           invariant already guarantees termination
 */
public record RegionSolveInput(
    BiomeGraph graph,
    MacroGridConfig gridConfig,
    RegionPos region,
    Map<MacroCellPos, BiomeId> fixedBoundaryCells,
    long worldSeed,
    int seedSpacingBlocks,
    int backtrackDepthCap
) {

    public RegionSolveInput {
        if (seedSpacingBlocks <= 0) {
            throw new IllegalArgumentException("seedSpacingBlocks must be positive, got " + seedSpacingBlocks);
        }
        if (backtrackDepthCap < 0) {
            throw new IllegalArgumentException("backtrackDepthCap must be >= 0, got " + backtrackDepthCap);
        }
    }
}
