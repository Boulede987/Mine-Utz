package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;
import java.util.Map;

/**
 * @param assignment         biome-id per macro-cell, this region's cells only
 * @param warnings           non-empty when the backtrack depth cap was hit and a
 *                           seed had to fall back to a universal-filler biome —
 *                           signals a graph-config bug worth investigating (see
 *                           doc/ARCHITECTURE.md "Decided")
 * @param backtrackStepsUsed total backtrack steps across the whole solve — high
 *                           relative to seed count is a direct sign the graph is
 *                           over-constrained for this region's grid, independent
 *                           of whether it ever hit the cap
 */
public record RegionSolveResult(Map<MacroCellPos, BiomeId> assignment, List<String> warnings, int backtrackStepsUsed) {
}
