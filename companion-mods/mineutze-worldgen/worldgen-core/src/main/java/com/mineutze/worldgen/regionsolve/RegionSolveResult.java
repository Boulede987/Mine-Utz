package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;
import java.util.Map;

/**
 * @param assignment          biome-id per macro-cell, this region's cells only
 * @param warnings             always empty via {@link TriangleFieldGeometryRealization}'s
 *                             current hex-mesh + {@link EdgeDisplacementRasterizer} path -
 *                             kept for a possible future warning source, unused today
 * @param droppedDemandCount  always 0 today, same reason as {@code warnings}
 */
public record RegionSolveResult(Map<MacroCellPos, BiomeId> assignment, List<String> warnings, int droppedDemandCount) {
}
