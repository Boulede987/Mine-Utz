package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;
import java.util.Map;

/**
 * @param assignment          biome-id per macro-cell, this region's cells only
 * @param warnings             non-empty when {@link BudgetPlanner} committed to a
 *                             patch that ultimately couldn't find a legal spot
 *                             anywhere in the region — never forced or shrunk,
 *                             just dropped; its space goes to whatever
 *                             {@link GapFiller} extends into it afterward.
 *                             Should be rare — total demand is bounded to fit
 *                             the region's capacity before placement even
 *                             starts, so this is a genuine adjacency conflict,
 *                             not an over-subscription artifact.
 * @param droppedDemandCount  how many of the budgeted patches got dropped —
 *                            same information as {@code warnings.size()},
 *                            exposed directly for logging/diagnosis without
 *                            parsing warning strings
 */
public record RegionSolveResult(Map<MacroCellPos, BiomeId> assignment, List<String> warnings, int droppedDemandCount) {
}
