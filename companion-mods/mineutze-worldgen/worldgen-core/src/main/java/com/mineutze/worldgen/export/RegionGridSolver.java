package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.PatchSizeEnforcer;
import com.mineutze.worldgen.regionsolve.PixelFragmentRepair;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.MeshGeometryRealization;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.SeedRegionCache;
import com.mineutze.worldgen.regionsolve.TopologyFirstRegionSolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Drives {@link SeedRegionCache} — the same in-memory, boundary-stitching
 * solve path density-function evaluation uses in-game — over an NxN swath
 * of regions, so a standalone run actually exercises cross-region
 * fixedBoundaryCells stitching instead of solving regions in isolation.
 *
 * <p>Then runs {@link PatchSizeEnforcer} and {@link PixelFragmentRepair} over
 * the WHOLE combined swath, no region-ownership restriction (every cell is
 * fair game) — something only a finite, one-shot export can do. Each
 * individual region's own per-region passes can only see neighbors that had
 * ALREADY solved by the time IT solved; a region solved FIRST has zero
 * knowledge of what a not-yet-solved neighbor will place right next to it,
 * and once solved a region's output is permanent — see {@link
 * PixelFragmentRepair}'s own doc. That's a real, order-dependent gap, found
 * via two separate direct user reports on the SAME root cause: a 5x5 export
 * showed 4 illegal adjacencies and several small same-biome fragments a 3x3
 * export of the same seed didn't even have the DATA to detect, and — for
 * {@link PatchSizeEnforcer} specifically — an 8-cell ocean patch entirely
 * surrounded by 3 different not-yet-solved-at-the-time neighbor regions,
 * nowhere near ocean's real ~88-cell minimum. All of that is exactly what a
 * whole-swath pass over the FULLY assembled export can still clean up, even
 * though the live game's per-region cache can't (revisiting an already-
 * committed region there would break the "solve once, cache forever"
 * property infinite lazy world generation depends on). Size enforcement
 * runs FIRST since it's the coarser, more fundamental fix (an undersized
 * patch not meeting its biome's own design range); fragment repair runs
 * after to mop up anything left over, including whatever a merge/widen
 * might itself have touched. This changes ONLY exported output for visual/
 * data review — {@link SeedRegionCache} itself, and therefore the live
 * game, is completely untouched by this class.
 */
public final class RegionGridSolver {

    private RegionGridSolver() {
    }

    public static ExportSnapshot solve(
        BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed,
        RegionPos minRegion, int regionSpan, BiConsumer<RegionPos, RegionSolveResult> onRegionSolved
    ) {
        return solve(graph, gridConfig, worldSeed, minRegion, regionSpan, onRegionSolved, MeshGeometryRealization::realize);
    }

    /** @param geometryRealizer defaults to {@link MeshGeometryRealization#realize} — same realizer the live game uses via {@link SeedRegionCache}. */
    public static ExportSnapshot solve(
        BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed,
        RegionPos minRegion, int regionSpan, BiConsumer<RegionPos, RegionSolveResult> onRegionSolved,
        TopologyFirstRegionSolver.GeometryRealizer geometryRealizer
    ) {
        SeedRegionCache cache = new SeedRegionCache(
            graph, gridConfig, worldSeed,
            (region, result, durationMillis) -> onRegionSolved.accept(region, result),
            geometryRealizer
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

        // cells.keySet() directly, not Set.copyOf(cells.keySet()) - no copy needed at all:
        // PatchSizeEnforcer only ever overwrites an EXISTING key's value here, never adds
        // or removes one, so the live key-set view stays valid and correct throughout.
        // Also sidesteps a real, measured pitfall: Set.copyOf's immutable-set
        // implementation is catastrophically slow for a large MacroCellPos collection
        // (~500x a plain HashSet at ~280k elements) - moot at small export spans, real
        // once macroCellSizeBlocks gets fine enough to push a swath into six figures.
        PatchSizeEnforcer.enforceMinimumPatchSize(cells, cells.keySet(), gridConfig, graph);
        Map<MacroCellPos, BiomeId> cleaned = PixelFragmentRepair.repair(cells, Map.of(), graph);

        // Collections.unmodifiableMap, not Map.copyOf - same pathological cost as
        // Set.copyOf (see above) for a large map, and unnecessary here regardless: cleaned
        // is a fresh local never aliased elsewhere, so a zero-copy read-only VIEW gives the
        // exact same practical immutability as a real copy would, at O(1) instead of
        // O(n)-with-a-bad-constant.
        return new ExportSnapshot(graph, gridConfig, worldSeed, minRegion, regionSpan, Collections.unmodifiableMap(cleaned), Map.copyOf(warningsByRegion));
    }
}
