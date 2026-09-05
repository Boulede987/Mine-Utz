package com.mineutze.worldgen.export.hex;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.SeedMeshCache;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A real neighborhood of cells, queried one at a time through {@link SeedMeshCache}'s own
 * public API — exactly the same grid-free, organic-cluster path real terrain generation
 * uses (unlike {@link HexBiomeRasterVisual}, which renders one raw mesh window directly,
 * with no caching/batching concept at all). This is the direct visual regression check
 * for the straight-seam bug the region-partition removal fixed (see
 * doc/decisions/2026-08-29-remove-region-stitching.md and this whole redesign's own plan
 * doc): since there is no fixed window or partition left anywhere in the mechanism, any
 * remaining straight line in the render would mean a real bug in the cluster/store
 * mechanism itself, not an expected limitation.
 */
public final class HexRegionGridVisual {

    private static final long WORLD_SEED = 2026L;
    private static final MacroGridConfig GRID_CONFIG = MacroGridConfig.DEFAULT;
    // Several multiples of one cluster's own worst-case footprint
    // (2*CLUSTER_GROWTH_CAP_RADIUS_BLOCKS=5000 blocks=1250 cells) so many real biome
    // patches and several cluster boundaries show up in one render - each individual
    // biomeAt call is a real, if small, cost (see this class's own doc; a bulk-friendly
    // debug API is a real possible follow-up, not built here), so this is a genuine
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
