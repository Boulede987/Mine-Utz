package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.BiomeConfig;
import com.mineutze.worldgen.biomesource.BiomeConfigRegistry;
import com.mineutze.worldgen.biomesource.TerrainClass;
import com.mineutze.worldgen.biomesource.TerrainClassifier;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Surface (2D) tier of the patch-growth engine. Resolves a block column's biome by
 * looking up which fixed-size TILE it falls in, resolving that whole tile via
 * PatchGrowthEngine on first touch, and caching it in a ConcurrentHashMap keyed by
 * tile coordinate (DESIGN.md Sec 5's "memoized by coarse region coordinate").
 * {@code computeIfAbsent} gives two things for free that an earlier ad-hoc, per-query
 * "window centered on wherever you asked" design did NOT: (1) real concurrency --
 * ConcurrentHashMap locks only the contended tile's bucket, so unrelated tiles resolve
 * on separate threads in true parallel, not serialized behind one instance-wide lock;
 * and (2) gaps (cells no patch's growth reached -- expected now that real biome sizes
 * exist, not a bug) are permanently known once their tile is resolved, instead of
 * silently triggering a full recompute the next time some other nearby query happened
 * to land on that exact gap cell. Both were real, measured problems with the earlier
 * design (see git history / the world-creation hang investigation), not theoretical.
 *
 * Tradeoff accepted deliberately: patches are now hard-clipped at tile edges, which are
 * fixed and grid-aligned rather than centered on wherever a player happens to be. At
 * TILE_WIDTH_CELLS * CELL_SIZE_BLOCKS (currently ~1300 blocks) apart, this is nothing
 * like the original chunk-aligned (16-block) square-border problem this whole project
 * exists to fix -- a seam here needs deliberate cross-country travel to even notice.
 *
 * Only ever depends on the surface tier's own state -- never calls into the underground
 * tier -- keep it that way (see UndergroundPatchGrid's class comment, once it exists at
 * Milestone 5, for why a reverse dependency would be a correctness trap).
 */
public final class SurfacePatchGrid {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int CELL_SIZE_BLOCKS = 16;

    // Tile radius in coarse cells (tile spans [-TILE_RADIUS_CELLS, +TILE_RADIUS_CELLS]
    // around its own centre, i.e. TILE_WIDTH_CELLS = 2*radius+1 cells wide). Growth is
    // hard-capped at the tile boundary regardless of target radius (PatchGrowthEngine's
    // WindowBounds) -- that cap is what fixed a real hang: an earlier, effectively
    // unbounded placeholder size config grew forever outward on every cache miss.
    //
    // Cost is quadratic in this radius (one real Climate.Sampler call per cell,
    // unconditionally, before growth even starts) -- a SECOND hang happened after simply
    // raising one biome's size_max in config, since this radius derived directly from
    // MAX_SIZE_MAX_BLOCKS with no ceiling. HARD_RADIUS_CEILING_CELLS exists so no single
    // future config value -- however reasonable a biome size it represents -- can do
    // that again. Biomes whose size_max exceeds what the ceiling comfortably covers will
    // occasionally get truncated at the tile edge instead of reaching their real target
    // radius -- an accepted, documented tradeoff (DESIGN.md Sec 6's own "cap lookahead"
    // philosophy explicitly sanctions this over unbounded cost), not a bug to chase away
    // by raising the ceiling again.
    private static final double TILE_MARGIN_FACTOR = 1.25;
    private static final int TILE_RADIUS_FLOOR_CELLS = 16;
    private static final int HARD_RADIUS_CEILING_CELLS = 40;
    private static final int TILE_RADIUS_CELLS = Math.min(
        HARD_RADIUS_CEILING_CELLS,
        Math.max(
            TILE_RADIUS_FLOOR_CELLS,
            (int) Math.ceil(BiomeConfigRegistry.MAX_SIZE_MAX_BLOCKS / 2.0 / CELL_SIZE_BLOCKS * TILE_MARGIN_FACTOR)
        )
    );
    private static final int TILE_WIDTH_CELLS = 2 * TILE_RADIUS_CELLS + 1;

    // Debug-measurement flood-fill (see measurePatchAt) never visits more than this many
    // cells -- a safety cap, not a tuned value; contiguous same-biome cache regions should
    // stay well under it in practice. Does not cross tile boundaries (see its own doc).
    private static final int DEBUG_MEASURE_CELL_CAP = 20_000;

    // TEMPORARY diagnostics from the world-creation hang investigation -- keep until the
    // tile-grid fix has been confirmed to actually resolve it under real play, then trim
    // back to just the summary line.
    private static final long SLOW_LOG_THRESHOLD_NANOS = 50_000_000L; // 50ms
    private static final long SUMMARY_LOG_EVERY_N_TILES = 25L;
    private static final AtomicLong DIAGNOSTIC_TILE_COUNT = new AtomicLong();
    private static final AtomicLong DIAGNOSTIC_TOTAL_COMPUTE_NANOS = new AtomicLong();

    static {
        LOGGER.info(
            "[patch-debug] SurfacePatchGrid tile width = {} cells ({} blocks each side), cell size = {} blocks",
            TILE_WIDTH_CELLS, TILE_WIDTH_CELLS * CELL_SIZE_BLOCKS, CELL_SIZE_BLOCKS
        );
    }

    private record TileKey(int tileX, int tileZ) {
    }

    private final long worldSeed;
    private final List<BiomeConfig> biomes;
    private final ConcurrentHashMap<TileKey, Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>>> tileCache = new ConcurrentHashMap<>();

    public SurfacePatchGrid(long worldSeed, List<BiomeConfig> biomes) {
        this.worldSeed = worldSeed;
        this.biomes = biomes;
    }

    /**
     * Resolves the biome at a block column, or returns null if no configured biome's
     * terrain_classes cover this cell and no neighboring patch's growth reached it --
     * the caller (CustomOverworldBiomeSource) must fall back to vanilla in that case,
     * never invent an arbitrary biome for the gap.
     */
    public ResourceKey<Biome> resolve(int blockX, int blockZ, Climate.Sampler sampler) {
        int cellX = Math.floorDiv(blockX, CELL_SIZE_BLOCKS);
        int cellZ = Math.floorDiv(blockZ, CELL_SIZE_BLOCKS);
        TileKey tileKey = new TileKey(Math.floorDiv(cellX, TILE_WIDTH_CELLS), Math.floorDiv(cellZ, TILE_WIDTH_CELLS));
        Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>> tile = tileCache.computeIfAbsent(tileKey, key -> resolveTile(key, sampler));
        return tile.get(new PatchGrowthEngine.CoarseCell(cellX, cellZ));
    }

    /** Resolves one whole tile. Runs inside ConcurrentHashMap.computeIfAbsent, so this
     * must never call back into tileCache (re-entrant computeIfAbsent on the same map
     * throws) -- it doesn't, PatchGrowthEngine is pure computation. */
    private Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>> resolveTile(TileKey key, Climate.Sampler sampler) {
        long startedAtNanos = System.nanoTime();
        int minCellX = key.tileX() * TILE_WIDTH_CELLS;
        int minCellZ = key.tileZ() * TILE_WIDTH_CELLS;
        PatchGrowthEngine.TerrainSampler terrainSampler = (cellX, cellZ) -> classifyCell(cellX, cellZ, sampler);
        Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>> resolved = PatchGrowthEngine.resolveWindow(
            worldSeed, biomes, CELL_SIZE_BLOCKS, terrainSampler,
            minCellX, minCellZ, minCellX + TILE_WIDTH_CELLS, minCellZ + TILE_WIDTH_CELLS
        );
        recordDiagnostics(key, System.nanoTime() - startedAtNanos, resolved.size());
        return resolved;
    }

    private void recordDiagnostics(TileKey key, long computeNanos, int cellsResolved) {
        long tileIndex = DIAGNOSTIC_TILE_COUNT.incrementAndGet();
        long totalComputeNanos = DIAGNOSTIC_TOTAL_COMPUTE_NANOS.addAndGet(computeNanos);

        if (computeNanos >= SLOW_LOG_THRESHOLD_NANOS) {
            LOGGER.info(
                "[patch-debug] tile ({}, {}) resolved on thread '{}': compute={}ms cells={} (of {} possible)",
                key.tileX(), key.tileZ(), Thread.currentThread().getName(),
                computeNanos / 1_000_000, cellsResolved, TILE_WIDTH_CELLS * TILE_WIDTH_CELLS
            );
        }
        if (tileIndex % SUMMARY_LOG_EVERY_N_TILES == 0) {
            LOGGER.info(
                "[patch-debug] summary after {} tiles: total compute={}ms avg compute={}ms/tile",
                tileIndex, totalComputeNanos / 1_000_000, (totalComputeNanos / tileIndex) / 1_000_000
            );
        }
    }

    /**
     * Debug helper for /dump_patch_debug: resolves the column, then flood-fills through
     * already-cached cells sharing the same biome id to estimate that patch's size.
     * Deliberately does NOT trigger new tile resolutions during the flood-fill (stays
     * cheap, only reports what's already been visited) -- so this can under-report a
     * patch that extends beyond what's been explored so far, and never crosses into an
     * unresolved neighboring tile. Good enough to confirm "does this patch's width fall
     * in its configured [size_min, size_max] range", not a precise or authoritative query.
     */
    public PatchMeasurement measurePatchAt(int blockX, int blockZ, Climate.Sampler sampler) {
        ResourceKey<Biome> biome = resolve(blockX, blockZ, sampler);
        if (biome == null) {
            return null;
        }
        PatchGrowthEngine.CoarseCell origin = new PatchGrowthEngine.CoarseCell(
            Math.floorDiv(blockX, CELL_SIZE_BLOCKS), Math.floorDiv(blockZ, CELL_SIZE_BLOCKS)
        );

        Set<PatchGrowthEngine.CoarseCell> visited = new HashSet<>();
        Deque<PatchGrowthEngine.CoarseCell> frontier = new ArrayDeque<>();
        visited.add(origin);
        frontier.push(origin);
        while (!frontier.isEmpty() && visited.size() < DEBUG_MEASURE_CELL_CAP) {
            PatchGrowthEngine.CoarseCell cell = frontier.pop();
            for (int[] offset : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                PatchGrowthEngine.CoarseCell neighbor = new PatchGrowthEngine.CoarseCell(cell.cellX() + offset[0], cell.cellZ() + offset[1]);
                if (visited.contains(neighbor) || !biome.equals(peekCachedBiome(neighbor))) {
                    continue;
                }
                visited.add(neighbor);
                frontier.push(neighbor);
            }
        }
        double approxRadiusBlocks = PatchGrowthEngine.patchRadiusFromCellCount(visited.size(), CELL_SIZE_BLOCKS);
        return new PatchMeasurement(biome, visited.size(), approxRadiusBlocks);
    }

    /** Read-only lookup that never triggers a tile resolution -- returns null both for
     * "tile not resolved yet" and "resolved, but this cell is a gap"; the debug
     * flood-fill in measurePatchAt doesn't need to tell those apart. */
    private ResourceKey<Biome> peekCachedBiome(PatchGrowthEngine.CoarseCell cell) {
        TileKey tileKey = new TileKey(Math.floorDiv(cell.cellX(), TILE_WIDTH_CELLS), Math.floorDiv(cell.cellZ(), TILE_WIDTH_CELLS));
        Map<PatchGrowthEngine.CoarseCell, ResourceKey<Biome>> tile = tileCache.get(tileKey);
        return tile == null ? null : tile.get(cell);
    }

    public record PatchMeasurement(ResourceKey<Biome> biome, int cachedCellCount, double approxRadiusBlocks) {
    }

    private static TerrainClass classifyCell(int cellX, int cellZ, Climate.Sampler sampler) {
        int blockX = cellX * CELL_SIZE_BLOCKS + CELL_SIZE_BLOCKS / 2;
        int blockZ = cellZ * CELL_SIZE_BLOCKS + CELL_SIZE_BLOCKS / 2;
        Climate.TargetPoint sample = sampler.sample(blockX >> 2, 0, blockZ >> 2);
        return TerrainClassifier.classify(
            Climate.unquantizeCoord(sample.continentalness()),
            Climate.unquantizeCoord(sample.erosion()),
            Climate.unquantizeCoord(sample.weirdness())
        );
    }
}
