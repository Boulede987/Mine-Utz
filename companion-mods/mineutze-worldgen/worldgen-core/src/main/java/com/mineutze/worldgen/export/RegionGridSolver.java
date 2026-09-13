package com.mineutze.worldgen.export;

import com.mineutze.worldgen.regionsolve.pixelgrid.PatchSizeEnforcer;

import com.mineutze.worldgen.regionsolve.pixelgrid.PixelFragmentRepair;

import com.mineutze.worldgen.regionsolve.windowcache.SeedRegionCache;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Drives {@link SeedRegionCache} - same in-memory, boundary-stitching solve path
 * density-function evaluation uses in-game - over NxN swath of regions, so standalone
 * run exercises cross-region fixedBoundaryCells stitching, not isolated regions.
 *
 * <p>Then runs {@link PatchSizeEnforcer} and {@link PixelFragmentRepair} over the WHOLE
 * combined swath, no region-ownership restriction - only a finite one-shot export can do
 * this. Per-region passes only see neighbors ALREADY solved by their own turn; a region
 * solved FIRST has zero knowledge of what a not-yet-solved neighbor places next to it,
 * and once solved, permanent - see {@link PixelFragmentRepair}'s own doc. Real,
 * order-dependent gap, found via 2 direct user reports, same root cause: 5x5 export
 * showed 4 illegal adjacencies + several small same-biome fragments a 3x3 export of the
 * same seed didn't even have the DATA to detect; for {@link PatchSizeEnforcer}
 * specifically, an 8-cell ocean patch surrounded by 3 not-yet-solved neighbor regions,
 * nowhere near ocean's real ~88-cell minimum. Whole-swath pass over FULLY assembled
 * export cleans this up; live game's per-region cache can't (revisiting a committed
 * region would break "solve once, cache forever," which infinite lazy world gen depends
 * on). Size enforcement runs FIRST (coarser, more fundamental fix - undersized patch
 * missing its biome's own design range); fragment repair mops up after, including
 * whatever merge/widen touched. Changes ONLY exported output for visual/data review -
 * {@link SeedRegionCache} and the live game are untouched by this class.
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

        // cells.keySet() directly, not Set.copyOf - no copy needed: PatchSizeEnforcer only
        // overwrites EXISTING keys here, never adds/removes, so live key-set view stays valid.
        // Also sidesteps Set.copyOf's immutable-set impl being catastrophically slow for large
        // MacroCellPos collections (~500x plain HashSet at ~280k elements) - moot at small
        // export spans, real once macroCellSizeBlocks gets fine enough to push swath to 6 figures.
        PatchSizeEnforcer.enforceMinimumPatchSize(cells, cells.keySet(), gridConfig, graph);
        Map<MacroCellPos, BiomeId> cleaned = PixelFragmentRepair.repair(cells, Map.of(), graph);

        // Collections.unmodifiableMap, not Map.copyOf - same pathological cost as Set.copyOf
        // (above) for large maps, unnecessary anyway: cleaned is a fresh local never aliased
        // elsewhere, so zero-copy read-only VIEW = same practical immutability at O(1), not
        // O(n)-with-bad-constant.
        return new ExportSnapshot(graph, gridConfig, worldSeed, minRegion, regionSpan, Collections.unmodifiableMap(cleaned), Map.copyOf(warningsByRegion));
    }
}
