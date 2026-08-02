package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything a writer ({@link JsonExporter}, {@link AsciiExporter},
 * {@link PngExporter}) needs to render one solved NxN swath of regions,
 * bundled so every writer sees the exact same run instead of each
 * re-deriving bounds/config independently.
 *
 * @param regionResults every solved region's own result (assignment +
 *                      warnings), keyed by region — NOT flattened into one
 *                      big cell map, so writers can still tell which
 *                      warnings came from which region
 */
public record ExportSnapshot(
    BiomeGraph graph,
    MacroGridConfig gridConfig,
    long worldSeed,
    RegionPos minRegion,
    int regionSpan,
    Map<RegionPos, RegionSolveResult> regionResults
) {

    /** Cell-grid side length of the whole solved swath, in macro-cells. */
    public int spanCells() {
        return regionSpan * gridConfig.regionSizeCellsPerSide();
    }

    /** Every macro-cell in the swath, ordered x-major then z — the iteration order every writer uses, so outputs line up. */
    public List<MacroCellPos> orderedCells() {
        List<MacroCellPos> cells = new ArrayList<>(spanCells() * spanCells());
        for (int dx = 0; dx < regionSpan; dx++) {
            for (int dz = 0; dz < regionSpan; dz++) {
                cells.addAll(gridConfig.cellsInRegion(new RegionPos(minRegion.x() + dx, minRegion.z() + dz)));
            }
        }
        cells.sort(java.util.Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z));
        return cells;
    }

    /** Resolved biome at a cell within this swath — never null, every cell in a solved region is always assigned (see GapFiller). */
    public BiomeId biomeAt(MacroCellPos cell) {
        RegionSolveResult result = regionResults.get(gridConfig.regionOf(cell));
        if (result == null) {
            throw new IllegalArgumentException(cell + " falls outside the solved region swath (region " + gridConfig.regionOf(cell) + ")");
        }
        BiomeId biome = result.assignment().get(cell);
        if (biome == null) {
            throw new IllegalStateException(cell + " has no assignment in its own region's solve result — should be impossible, every cell gets assigned");
        }
        return biome;
    }
}
