package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;
import java.util.Map;

/**
 * @param assignment          biome-id per macro-cell, this region's cells only
 * @param warnings             always empty via {@link TriangleFieldGeometryRealization}'s
 *                             current {@link SeamGrowthRasterizer}-based path — kept for
 *                             a possible future warning source, not actively populated
 *                             by anything today
 * @param droppedDemandCount  always 0 via the current path, same reasoning as
 *                            {@code warnings} above
 */
public record RegionSolveResult(Map<MacroCellPos, BiomeId> assignment, List<String> warnings, int droppedDemandCount) {
}
