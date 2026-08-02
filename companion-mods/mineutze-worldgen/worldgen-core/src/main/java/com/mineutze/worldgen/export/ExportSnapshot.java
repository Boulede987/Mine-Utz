package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything a writer ({@link JsonExporter}, {@link AsciiExporter},
 * {@link PngExporter}) needs to render one NxN swath of regions, bundled so
 * every writer sees the exact same run instead of each re-deriving
 * bounds/config independently.
 *
 * Deliberately just a flat cell map, not solver-specific — {@link
 * RegionGridSolver} builds one from {@code RegionSolver}, but the exporters
 * themselves have no idea (or need to know) whether a cell's biome came
 * from solving or from reading a real generated world, which is exactly
 * what lets the same three writer classes serve both the standalone CLI and
 * the in-game "export what actually generated" command.
 *
 * @param warningsByRegion solver diagnostics, empty for any region with
 *                          none to report (e.g. every region, always, for a
 *                          real-world snapshot — there's no such thing as a
 *                          dropped demand once terrain already exists)
 */
public record ExportSnapshot(
    BiomeGraph graph,
    MacroGridConfig gridConfig,
    long worldSeed,
    RegionPos minRegion,
    int regionSpan,
    Map<MacroCellPos, BiomeId> cells,
    Map<RegionPos, List<String>> warningsByRegion
) {

    /** Cell-grid side length of the whole swath, in macro-cells. */
    public int spanCells() {
        return regionSpan * gridConfig.regionSizeCellsPerSide();
    }

    /** Every macro-cell in the swath, ordered x-major then z — the iteration order every writer uses, so outputs line up. */
    public List<MacroCellPos> orderedCells() {
        List<MacroCellPos> result = new ArrayList<>(spanCells() * spanCells());
        for (int dx = 0; dx < regionSpan; dx++) {
            for (int dz = 0; dz < regionSpan; dz++) {
                result.addAll(gridConfig.cellsInRegion(new RegionPos(minRegion.x() + dx, minRegion.z() + dz)));
            }
        }
        result.sort(java.util.Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z));
        return result;
    }

    /** Biome at a cell within this swath — never null for a properly-built snapshot. */
    public BiomeId biomeAt(MacroCellPos cell) {
        BiomeId biome = cells.get(cell);
        if (biome == null) {
            throw new IllegalArgumentException(cell + " has no biome in this snapshot (outside the swath, or a gap in a real-world scan)");
        }
        return biome;
    }
}
