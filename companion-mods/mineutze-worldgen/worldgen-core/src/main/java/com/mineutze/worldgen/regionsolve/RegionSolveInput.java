package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;

/**
 * @param fixedBoundaryCells edge cells from whichever neighbor regions are
 *                           already solved (any of the 4 directions —
 *                           direction-agnostic, see doc/ARCHITECTURE.md),
 *                           fixed and never claimed by this solve
 */
public record RegionSolveInput(
    BiomeGraph graph,
    MacroGridConfig gridConfig,
    RegionPos region,
    Map<MacroCellPos, BiomeId> fixedBoundaryCells,
    long worldSeed
) {
}
