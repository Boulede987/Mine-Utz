package com.mineutze.worldgen.biomesource.patch;

import com.mineutze.worldgen.biomesource.BiomeConfig;
import com.mineutze.worldgen.biomesource.BiomeConfigRegistry;
import com.mineutze.worldgen.biomesource.RegionFamily;
import com.mineutze.worldgen.biomesource.TerrainClass;
import com.mineutze.worldgen.biomesource.TerrainClassifier;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
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
 * Tradeoff accepted deliberately: patches are hard-clipped at tile edges, which are fixed
 * and grid-aligned rather than centered on wherever a player happens to be. This was
 * originally assumed to be a rare, barely-noticeable edge case given how far apart tile
 * seams are -- that assumption was wrong in practice: with TILE_RADIUS_CELLS capped
 * below what some configured biomes' size_max actually needed, clipping was the DEFAULT
 * outcome for those biomes, not a rare one, and produced clearly visible rectangular
 * patches. The real fix was making sure the radius ceiling comfortably exceeds current
 * config (see its own comment) so patches terminate by their own natural target radius
 * in the common case -- tile-edge clipping remains a real possibility for any future
 * biome sized well past what's currently configured, and would still show as a straight
 * edge if it happens, not a curve.
 *
 * Only ever depends on the surface tier's own state -- never calls into the underground
 * tier -- keep it that way (see {@link UndergroundPatchGrid}'s class comment for why a
 * reverse dependency would be a correctness trap).
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
    // future config value can reintroduce that.
    //
    // The ceiling was originally set to 40 (the pre-hang-fix value), which turned out to
    // be a real bug of its own, not just a safety margin: with desert's size_max at 1900
    // blocks (radius 950 -> ~59 cells), a ceiling of 40 meant desert NEVER terminated by
    // its own natural target radius -- it always hit the tile's rectangular edge first,
    // producing visibly square/rectangular patches instead of organic ones. Raised well
    // past what the current config needs (75 cells with margin) so the ceiling goes back
    // to being a rare-case safety bound, not the routine limiter. Biomes whose size_max
    // exceeds what the ceiling comfortably covers will still occasionally get truncated
    // at the tile edge instead of reaching their real target radius -- an accepted,
    // documented tradeoff (DESIGN.md Sec 6's own "cap lookahead" philosophy explicitly
    // sanctions this over unbounded cost) -- but that should now be the rare exception,
    // not the default outcome for any moderately large biome.
    private static final double TILE_MARGIN_FACTOR = 1.25;
    private static final int TILE_RADIUS_FLOOR_CELLS = 16;
    private static final int HARD_RADIUS_CEILING_CELLS = 128;
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
    private final Map<ResourceKey<Biome>, BiomeConfig> configById;
    private final ConcurrentHashMap<TileKey, PatchGrowthEngine.WindowResolution> tileCache = new ConcurrentHashMap<>();

    public SurfacePatchGrid(long worldSeed, List<BiomeConfig> biomes) {
        this.worldSeed = worldSeed;
        this.biomes = biomes;
        Map<ResourceKey<Biome>, BiomeConfig> lookup = new HashMap<>();
        for (BiomeConfig biome : biomes) {
            lookup.put(biome.id(), biome);
        }
        this.configById = lookup;
    }

    /**
     * Resolves the biome at a block column, or returns null if no configured biome's
     * terrain_classes cover this cell and no neighboring patch's growth reached it, OR
     * if the exact block's real terrain doesn't actually match what got resolved (see
     * acceptIfEligibleForExactBlock below) -- the caller (CustomOverworldBiomeSource)
     * must fall back in either case, never invent an arbitrary biome for the gap.
     *
     * Domain-warps the lookup near boundaries so edges wobble organically instead of
     * following the underlying cell grid exactly (see DomainWarp's class comment for
     * why the warp itself must be smooth). Safety property that must never be violated:
     * whatever biome this returns for (blockX, blockZ) is always terrain-class-eligible
     * for the REAL terrain at that exact position -- a warped/borrowed answer is only
     * ever accepted after re-checking it against the true, unwarped terrain
     * classification here, so the warp can never place e.g. desert on terrain the real
     * noise says is ocean. The underlying cell/tile structure (including small-island
     * dissolve) is resolved once, cached, and untouched by any of this -- warping is a
     * read-time-only cosmetic layer on top of an already-correct structure.
     *
     * Second safety property, added after a confirmed real symptom (desert visibly
     * overlapping real river channels): classifyCell only ever samples ONE point per
     * CELL_SIZE_BLOCKS=16 cell (its midpoint) to drive every placement decision for the
     * whole cell -- a real feature narrower than one cell (RIVER especially) can sit
     * anywhere in that cell without the midpoint ever seeing it. acceptIfEligibleForExactBlock
     * re-validates the FINAL answer (true or warp-accepted) against the exact queried
     * block's own real terrain class before returning it, on top of the warp-specific
     * check above.
     */
    public ResourceKey<Biome> resolve(int blockX, int blockZ, Climate.Sampler sampler) {
        BiomeAndFamily result = resolveWithFamily(blockX, blockZ, sampler);
        return result == null ? null : result.biome();
    }

    /** Phase 2 (TERRAIN_COHERENCE_DESIGN.md Sec 4b): same warp+re-validate logic as
     * resolve(), but carrying the cell's rolled RegionFamily alongside the biome through
     * every step -- one warp-acceptance code path for both, so a warped/borrowed answer
     * can never disagree between what CustomOverworldBiomeSource assigns as the real
     * biome and what a later stage's density function reads as shape intent right at the
     * same block. resolve() is now a thin wrapper around this. */
    public BiomeAndFamily resolveWithFamily(int blockX, int blockZ, Climate.Sampler sampler) {
        CellResolution trueResolution = resolveCellWithFamily(blockX, blockZ, sampler);
        if (trueResolution == null) {
            return null;
        }
        TerrainClass trueTerrainClassAtThisExactBlock = classifyBlock(blockX, blockZ, sampler);

        int warpedBlockX = blockX + DomainWarp.offsetXBlocks(worldSeed, blockX, blockZ);
        int warpedBlockZ = blockZ + DomainWarp.offsetZBlocks(worldSeed, blockX, blockZ);
        if (cellFor(warpedBlockX, warpedBlockZ).equals(cellFor(blockX, blockZ))) {
            // warp didn't reach a different cell -- nothing to borrow, still needs the
            // exact-block safety net below
            return acceptIfEligibleForExactBlock(trueResolution, trueTerrainClassAtThisExactBlock);
        }

        CellResolution warpedResolution = resolveCellWithFamily(warpedBlockX, warpedBlockZ, sampler);
        if (warpedResolution == null || warpedResolution.biome().equals(trueResolution.biome())) {
            return acceptIfEligibleForExactBlock(trueResolution, trueTerrainClassAtThisExactBlock);
        }

        BiomeConfig warpedConfig = configById.get(warpedResolution.biome());
        boolean accepted = warpedConfig != null && warpedConfig.terrainClasses().contains(trueTerrainClassAtThisExactBlock);
        recordWarpPairDiagnostics(trueResolution.biome(), warpedResolution.biome(), accepted);
        // rejected -- borrowing would mismatch the real terrain right here
        return accepted ? warpedResolution.toBiomeAndFamily() : acceptIfEligibleForExactBlock(trueResolution, trueTerrainClassAtThisExactBlock);
    }

    /** Safety net for something classifyCell's single-midpoint sampling structurally
     * cannot catch: a narrow real terrain feature (RIVER especially -- often narrower
     * than one CELL_SIZE_BLOCKS=16 cell) physically present at THIS exact block, even
     * though the cell's midpoint sample said something else and every placement decision
     * for the whole cell was made on that basis. Confirmed real symptom, not
     * hypothetical: land biomes (desert) visibly overlapping real river channels,
     * because germination/growth only ever sampled the cell midpoint.
     *
     * Uses PatchGrowthEngine.isEligibleForGrowth, NOT a strict terrainClasses().contains
     * check -- reusing growth's own permissiveness rule is what keeps this from falsely
     * rejecting legitimate FLAT_LAND/HILLY blending (e.g. windswept_hills grown onto a
     * neighboring FLAT_LAND block) while still catching what growth itself would never
     * have allowed: a real water-class (including RIVER) or MOUNTAINOUS mismatch.
     *
     * On rejection, returns null (a "gap") rather than the mismatched answer --
     * CustomOverworldBiomeSource's existing gap-fallback chain already does exactly the
     * right thing for that: it classifies the EXACT block, not a cell midpoint, and picks
     * a real, weighted-eligible biome for it (e.g. the configured RIVER biome). This only
     * ever narrows what gets returned here, never widens past the cell's already-resolved
     * eligibility -- can't undo any other safety property (small-island dissolve, warp
     * acceptance) already established elsewhere in this class. */
    private BiomeAndFamily acceptIfEligibleForExactBlock(CellResolution resolution, TerrainClass exactTerrainClass) {
        BiomeConfig config = configById.get(resolution.biome());
        if (config != null && PatchGrowthEngine.isEligibleForGrowth(config, exactTerrainClass)) {
            return resolution.toBiomeAndFamily();
        }
        return null;
    }

    /** Public: (biome, regionFamily) pair for one exact block position, after warp
     * acceptance -- regionFamily is null wherever the resolved biome declared no
     * regionFamilyWeights (see PatchGrowthEngine.GrowingPatch's own comment). */
    public record BiomeAndFamily(ResourceKey<Biome> biome, RegionFamily regionFamily) {
    }

    /** TEMPORARY diagnostics: per-(trueBiome, warpedBiome) accept/reject counts, so if
     * some specific biome pair's border stays suspiciously straight despite the warp
     * being active elsewhere, real data is available instead of guessing why. Remove
     * once border quality across all configured pairs is confirmed good in practice. */
    private static final Map<String, long[]> WARP_PAIR_STATS = new ConcurrentHashMap<>();
    private static final AtomicLong WARP_CONSIDERATION_COUNT = new AtomicLong();
    private static final long WARP_STATS_LOG_EVERY_N = 2000L;

    private static void recordWarpPairDiagnostics(ResourceKey<Biome> trueBiome, ResourceKey<Biome> warpedBiome, boolean accepted) {
        String pairKey = trueBiome.location() + " <-> " + warpedBiome.location();
        long[] counts = WARP_PAIR_STATS.computeIfAbsent(pairKey, key -> new long[2]);
        synchronized (counts) {
            counts[accepted ? 0 : 1]++;
        }
        if (WARP_CONSIDERATION_COUNT.incrementAndGet() % WARP_STATS_LOG_EVERY_N == 0) {
            StringBuilder summary = new StringBuilder("[patch-debug] warp accept/reject by biome pair (accepted/rejected):");
            for (Map.Entry<String, long[]> entry : WARP_PAIR_STATS.entrySet()) {
                summary.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue()[0]).append('/').append(entry.getValue()[1]);
            }
            LOGGER.info(summary.toString());
        }
    }

    private static PatchGrowthEngine.CoarseCell cellFor(int blockX, int blockZ) {
        return new PatchGrowthEngine.CoarseCell(Math.floorDiv(blockX, CELL_SIZE_BLOCKS), Math.floorDiv(blockZ, CELL_SIZE_BLOCKS));
    }

    /** Looks up a single cell's biome, resolving (and caching) its whole tile on first
     * touch. No warping here -- this is the "true", grid-aligned answer for exactly the
     * given block position. Thin wrapper around resolveCellWithFamily -- measurePatchAt
     * only ever needed the biome half. */
    private ResourceKey<Biome> resolveCell(int blockX, int blockZ, Climate.Sampler sampler) {
        CellResolution resolution = resolveCellWithFamily(blockX, blockZ, sampler);
        return resolution == null ? null : resolution.biome();
    }

    /** (biome, regionFamily) pair for one cell, resolving (and caching) its whole tile
     * on first touch. No warping here -- see resolveCell's own comment. */
    private CellResolution resolveCellWithFamily(int blockX, int blockZ, Climate.Sampler sampler) {
        PatchGrowthEngine.CoarseCell cell = cellFor(blockX, blockZ);
        TileKey tileKey = new TileKey(Math.floorDiv(cell.cellX(), TILE_WIDTH_CELLS), Math.floorDiv(cell.cellZ(), TILE_WIDTH_CELLS));
        PatchGrowthEngine.WindowResolution tile = tileCache.computeIfAbsent(tileKey, key -> resolveTile(key, sampler));
        ResourceKey<Biome> biome = tile.biomeByCell().get(cell);
        if (biome == null) {
            return null;
        }
        return new CellResolution(biome, tile.regionFamilyByCell().get(cell));
    }

    private record CellResolution(ResourceKey<Biome> biome, RegionFamily regionFamily) {
        BiomeAndFamily toBiomeAndFamily() {
            return new BiomeAndFamily(biome, regionFamily);
        }
    }

    /** Resolves one whole tile. Runs inside ConcurrentHashMap.computeIfAbsent, so this
     * must never call back into tileCache (re-entrant computeIfAbsent on the same map
     * throws) -- it doesn't, PatchGrowthEngine is pure computation.
     *
     * If this throws, computeIfAbsent records NO mapping for the tile -- every future
     * query touching it re-attempts resolution from scratch and throws again. Under real
     * chunk generation (many worker threads, the eager stronghold-ring structure scan
     * querying thousands of columns in a burst) that reads as a silent hang, not a
     * visible crash -- a real, confirmed failure mode this session (see
     * BiomeConfigRegistry's static-init-order bug and its own comment). Catch, log loudly
     * with the tile coordinates that triggered it, then rethrow -- never swallow. */
    private PatchGrowthEngine.WindowResolution resolveTile(TileKey key, Climate.Sampler sampler) {
        long startedAtNanos = System.nanoTime();
        int minCellX = key.tileX() * TILE_WIDTH_CELLS;
        int minCellZ = key.tileZ() * TILE_WIDTH_CELLS;
        try {
            PatchGrowthEngine.TerrainSampler terrainSampler = (cellX, cellZ) -> classifyCell(cellX, cellZ, sampler);
            PatchGrowthEngine.WindowResolution resolved = PatchGrowthEngine.resolveWindow(
                worldSeed, biomes, CELL_SIZE_BLOCKS, terrainSampler,
                minCellX, minCellZ, minCellX + TILE_WIDTH_CELLS, minCellZ + TILE_WIDTH_CELLS
            );
            recordDiagnostics(key, System.nanoTime() - startedAtNanos, resolved.biomeByCell().size());
            return resolved;
        } catch (RuntimeException | Error e) {
            LOGGER.error(
                "[patch-debug] tile ({}, {}) [{},{} to {},{}) FAILED to resolve on thread '{}' after {}ms -- "
                    + "this tile will keep re-throwing on every future query until fixed and redeployed",
                key.tileX(), key.tileZ(), minCellX, minCellZ, minCellX + TILE_WIDTH_CELLS, minCellZ + TILE_WIDTH_CELLS,
                Thread.currentThread().getName(), (System.nanoTime() - startedAtNanos) / 1_000_000, e
            );
            throw e;
        }
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
     * Uses the TRUE (unwarped) cell structure, not resolve()'s warped answer -- this
     * measures the authored patch, not the cosmetically wobbled visual outcome.
     */
    public PatchMeasurement measurePatchAt(int blockX, int blockZ, Climate.Sampler sampler) {
        ResourceKey<Biome> biome = resolveCell(blockX, blockZ, sampler);
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
        PatchGrowthEngine.WindowResolution tile = tileCache.get(tileKey);
        return tile == null ? null : tile.biomeByCell().get(cell);
    }

    /** Public, non-triggering peek at a cell's resolved region-family shape intent --
     * null for "tile not resolved yet", "resolved but this cell is a gap", AND "resolved
     * to a biome that declared no regionFamilyWeights" (see
     * PatchGrowthEngine.GrowingPatch's own comment) -- all three read identically to a
     * caller: stay reactive to real Tectonic terrain here. This is deliberately
     * non-triggering (mirrors peekCachedBiome's exact contract, one level over) because
     * a codec-constructed DensityFunction (Stage 3) has no Climate.Sampler to force a
     * real resolution with -- see the plan's "Climate.Sampler access problem" section.
     * No warp applied here either, same reasoning as peekCachedBiome: this is a cheap
     * read of already-resolved structure, not a fresh query. */
    public RegionFamily peekRegionIntent(int blockX, int blockZ) {
        PatchGrowthEngine.CoarseCell cell = cellFor(blockX, blockZ);
        TileKey tileKey = new TileKey(Math.floorDiv(cell.cellX(), TILE_WIDTH_CELLS), Math.floorDiv(cell.cellZ(), TILE_WIDTH_CELLS));
        PatchGrowthEngine.WindowResolution tile = tileCache.get(tileKey);
        return tile == null ? null : tile.regionFamilyByCell().get(cell);
    }

    public record PatchMeasurement(ResourceKey<Biome> biome, int cachedCellCount, double approxRadiusBlocks) {
    }

    /** Terrain class for a whole coarse cell, sampled once at its representative
     * midpoint -- what germination/growth/eligibility use throughout PatchGrowthEngine. */
    private static TerrainClass classifyCell(int cellX, int cellZ, Climate.Sampler sampler) {
        int blockX = cellX * CELL_SIZE_BLOCKS + CELL_SIZE_BLOCKS / 2;
        int blockZ = cellZ * CELL_SIZE_BLOCKS + CELL_SIZE_BLOCKS / 2;
        return classifyBlock(blockX, blockZ, sampler);
    }

    /** Terrain class at one EXACT block position, not a cell's representative midpoint --
     * what resolve()'s warp-acceptance check uses. A cell is classified once for the
     * whole 16-block area during growth; this finer per-block reading is what keeps a
     * borrowed warped answer honest about the real terrain right at the query position,
     * even though the cell it came from was only validated at its own midpoint. */
    private static TerrainClass classifyBlock(int blockX, int blockZ, Climate.Sampler sampler) {
        return TerrainClassifier.classifyAtBlock(sampler, blockX, blockZ);
    }
}
