package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.regionsolve.livepath.SeedMeshCache;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real neighborhood of cells, queried one at a time through {@link SeedMeshCache}'s own
 * public API - exact grid-free, organic-cluster path real terrain generation uses
 * (unlike {@link HexBiomeRasterVisual}, which renders one raw mesh window directly, no
 * caching/batching). Direct visual regression check for the straight-seam bug the
 * region-partition removal fixed (see doc/decisions/2026-08-29-remove-region-stitching.md
 * + redesign's own plan doc): no fixed window/partition left anywhere in the mechanism,
 * so any remaining straight line in render means a real bug in cluster/store mechanism,
 * not an expected limitation.
 */
public final class HexRegionGridVisual {

    private static final long WORLD_SEED = 2026L;
    private static final MacroGridConfig GRID_CONFIG = MacroGridConfig.DEFAULT;
    // Several multiples of one cluster's worst-case footprint
    // (2*CLUSTER_GROWTH_CAP_RADIUS_BLOCKS=5000 blocks=1250 cells) so many biome patches +
    // several cluster boundaries show up per render - each biomeAt call has real, small cost
    // (bulk-friendly debug API a possible follow-up, not built here) - genuine
    // multi-million-call export, not instant.
    private static final int HALF_WIDTH_CELLS = 900;

    private HexRegionGridVisual() {
    }

    public static void main(String[] args) {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "hex-mesh-export");

        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        SeedMeshCache cache = new SeedMeshCache(graph, GRID_CONFIG, WORLD_SEED);

        Map<MacroCellPos, BiomeId> combined = new LinkedHashMap<>();
        for (int x = -HALF_WIDTH_CELLS; x < HALF_WIDTH_CELLS; x++) {
            for (int z = -HALF_WIDTH_CELLS; z < HALF_WIDTH_CELLS; z++) {
                MacroCellPos cell = new MacroCellPos(x, z);
                combined.put(cell, cache.biomeAt(cell));
            }
            if (x % 128 == 0) {
                System.out.println("Solved column " + x + "/" + HALF_WIDTH_CELLS);
            }
        }

        List<MacroCellPos> cells = List.copyOf(combined.keySet());
        Map<BiomeId, Integer> palette = HexBiomeRasterVisual.evenlySpacedPalette(graph);
        Path rasterFile = outputDir.resolve("hex_region_grid_raster.png");
        Path legendFile = outputDir.resolve("hex_region_grid_legend.png");
        HexBiomeRasterVisual.writePng(HexBiomeRasterVisual.renderRaster(cells, combined, palette), rasterFile);
        HexBiomeRasterVisual.writePng(HexBiomeRasterVisual.renderLegend(graph, palette), legendFile);
        System.out.println("Wrote " + rasterFile.toAbsolutePath());
        System.out.println("Wrote " + legendFile.toAbsolutePath());
    }
}
