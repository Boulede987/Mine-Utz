package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared "never force-solve a neighbor" boundary logic — storage-agnostic,
 * so both the in-memory
 * {@link SeedRegionCache} (used during density evaluation, no ServerLevel
 * available) and the mod's SavedData-backed cache (used by the debug
 * command) can share it instead of duplicating the neighbor-scan.
 */
public final class RegionBoundaryCollector {

    private RegionBoundaryCollector() {
    }

    /**
     * @param alreadySolvedLookup returns the neighbor's assignment if
     *                            already solved, or null if not — must
     *                            never trigger solving one
     */
    public static Map<MacroCellPos, BiomeId> collect(
        RegionPos region, MacroGridConfig grid, long worldSeed, Function<RegionPos, Map<MacroCellPos, BiomeId>> alreadySolvedLookup
    ) {
        Map<MacroCellPos, BiomeId> fixedBoundary = new LinkedHashMap<>();
        for (RegionPos neighbor : neighborsOf(region)) {
            Map<MacroCellPos, BiomeId> neighborAssignment = alreadySolvedLookup.apply(neighbor);
            if (neighborAssignment != null) {
                fixedBoundary.putAll(cellsBorderingRegion(neighborAssignment, region, grid));
            }
        }
        return fixedBoundary;
    }

    /**
     * The full 8-cell Moore set, not just the 4 cardinal neighbors — the region grid's
     * own warp (see {@code MacroGridConfig}'s doc) displaces the vertical and horizontal
     * boundary lines independently, so a cell near a region's CORNER can warp
     * diagonally into a corner-adjacent region, not just a cardinal one. Cardinal-only
     * neighbors were correct back when the grid was a rigid square.
     */
    public static List<RegionPos> neighborsOf(RegionPos region) {
        return List.of(
            new RegionPos(region.x() - 1, region.z()),
            new RegionPos(region.x() + 1, region.z()),
            new RegionPos(region.x(), region.z() - 1),
            new RegionPos(region.x(), region.z() + 1),
            new RegionPos(region.x() - 1, region.z() - 1),
            new RegionPos(region.x() - 1, region.z() + 1),
            new RegionPos(region.x() + 1, region.z() - 1),
            new RegionPos(region.x() + 1, region.z() + 1)
        );
    }

    /**
     * Bounding-box-distance check (same idiom {@code TriangleFieldGeometryRealization
     * .relevantFaceIndices} already uses), not a per-cell {@code regionOf} stencil - a
     * region's own solve can contain real triangle faces whose centroids fall up to
     * {@code TriangleFieldGeometryRealization.TRIANGULATION_MARGIN_BLOCKS} blocks into an
     * already-solved neighbor (that margin exists specifically so every triangle touching
     * the region has real neighbor context), so a single-cell-wide ring at the literal seam
     * (the old behavior here) is far too thin to ever match one of those centroids against
     * this map. {@code TRIANGULATION_MARGIN_BLOCKS} is the exact bound past which no such
     * centroid can exist, so this is a precise depth, not a guess - and it's cheaper per
     * cell than before, since it no longer calls the warp-aware {@code regionOf} at all.
     */
    private static Map<MacroCellPos, BiomeId> cellsBorderingRegion(
        Map<MacroCellPos, BiomeId> neighborAssignment, RegionPos region, MacroGridConfig grid
    ) {
        int cellSize = grid.macroCellSizeBlocks();
        int sideBlocks = grid.regionSizeCellsPerSide() * cellSize;
        int warpMarginBlocks = grid.warpMarginCells() * cellSize;
        int depthBlocks = TriangleFieldGeometryRealization.TRIANGULATION_MARGIN_BLOCKS;

        int minX = region.x() * sideBlocks - warpMarginBlocks - depthBlocks;
        int maxX = region.x() * sideBlocks + sideBlocks + warpMarginBlocks + depthBlocks;
        int minZ = region.z() * sideBlocks - warpMarginBlocks - depthBlocks;
        int maxZ = region.z() * sideBlocks + sideBlocks + warpMarginBlocks + depthBlocks;

        Map<MacroCellPos, BiomeId> bordering = new LinkedHashMap<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : neighborAssignment.entrySet()) {
            MacroCellPos cell = entry.getKey();
            int blockX = cell.x() * cellSize;
            int blockZ = cell.z() * cellSize;
            if (blockX >= minX && blockX <= maxX && blockZ >= minZ && blockZ <= maxZ) {
                bordering.put(cell, entry.getValue());
            }
        }
        return bordering;
    }
}
