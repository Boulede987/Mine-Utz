package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.SeedRegionCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Drives {@link SeedRegionCache} — the same in-memory, boundary-stitching
 * solve path density-function evaluation uses in-game — over an NxN swath
 * of regions, so a standalone run actually exercises cross-region
 * fixedBoundaryCells stitching instead of solving regions in isolation.
 */
public final class RegionGridSolver {

    private RegionGridSolver() {
    }

    public static ExportSnapshot solve(
        BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed,
        RegionPos minRegion, int regionSpan, BiConsumer<RegionPos, RegionSolveResult> onRegionSolved
    ) {
        SeedRegionCache cache = new SeedRegionCache(
            graph, gridConfig, worldSeed,
            (region, result, durationMillis) -> onRegionSolved.accept(region, result)
        );

        Map<MacroCellPos, BiomeId> cells = new LinkedHashMap<>();
        Map<RegionPos, List<String>> warningsByRegion = new LinkedHashMap<>();
        for (int dx = 0; dx < regionSpan; dx++) {
            for (int dz = 0; dz < regionSpan; dz++) {
                RegionPos region = new RegionPos(minRegion.x() + dx, minRegion.z() + dz);
                RegionSolveResult result = cache.resultFor(region);
                cells.putAll(result.assignment());
                warningsByRegion.put(region, result.warnings());
            }
        }

        return new ExportSnapshot(graph, gridConfig, worldSeed, minRegion, regionSpan, Map.copyOf(cells), Map.copyOf(warningsByRegion));
    }
}
