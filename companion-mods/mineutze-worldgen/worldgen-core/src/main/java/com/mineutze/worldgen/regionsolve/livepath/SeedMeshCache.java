package com.mineutze.worldgen.regionsolve.livepath;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;

import com.mineutze.worldgen.regionsolve.MacroCellPos;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store-aware, GRID-FREE replacement for {@code SeedRegionCache} (see
 * doc/decisions/2026-08-29-remove-region-stitching.md and this redesign's plan doc). No
 * fixed-size window or partition anywhere - the CSP batching unit is an ORGANIC CLUSTER,
 * grown outward from wherever a query lands until it closes against already-decided
 * territory (see {@link TriangleFieldGeometryRealization#realizeCluster}'s doc). Fixes
 * the real failure mode a fixed-size window had: a face straddling a window boundary got
 * a throwaway, never-reconciled guess from whichever window solved first, producing a
 * visible straight seam on the window's edge. A cluster's boundary is never arbitrary -
 * it's exactly "everything this solve actually decided," so no leftover territory for a
 * later solve to disagree about.
 *
 * <p><b>Three-tier lookup, cheapest first:</b>
 * <ol>
 *   <li>{@code cellCache} — plain per-cell answer, once known, permanent (cell's biome
 *   never changes) - REPEATED query at same/recent cell costs one map read, no mesh,
 *   no lock.</li>
 *   <li>{@link TriangleFieldGeometryRealization#tryFastPathLookup} — new cell whose
 *   covering face already has SOME value (tentative or permanent - see {@link
 *   DecidedFaceStore}'s two-tier doc) from an EARLIER nearby query costs only a small
 *   local mesh + point-in-polygon test, no CSP. HIT checked unlocked first; promoting
 *   it (below) is a write, done under the lock.</li>
 *   <li>{@link TriangleFieldGeometryRealization#realizeCluster} — only genuinely new
 *   area (nothing decided nearby) pays for a real cluster growth + CSP solve, under the
 *   single global lock below.</li>
 * </ol>
 *
 * <p><b>Promotion</b> — whichever tier answers a query, the moment that answer reaches a
 * real caller (Minecraft placing real terrain with it), the covering face (and any
 * vertex triples now fully settled) promotes to PERMANENT via {@link
 * TriangleFieldGeometryRealization#promoteCoveringFaceToPermanent}/{@code
 * realizeCluster}'s internal promotion — irrevocable from there. Only real gameplay
 * (structure-search ring probes especially - see this redesign's plan doc for why) ever
 * locks a face in; everything else a cluster solve merely touches stays tentative,
 * freely revisable by a later solve.
 *
 * <p>Concurrency: same single-global-lock discipline {@code SeedRegionCache} used -
 * lock-free read for {@code cellCache} and the initial fast-path pre-check (both only
 * read the already-thread-safe {@link DecidedFaceStore}/{@link DecidedVertexStore}), one
 * lock serializing promotion and the whole slow-path solve-then-commit sequence so two
 * adjacent queries can never race into a stale view of each other.
 */
public final class SeedMeshCache {

    private final BiomeGraph graph;
    private final MacroGridConfig gridConfig;
    private final long worldSeed;
    private final DecidedFaceStore faceStore;
    private final DecidedVertexStore vertexStore;
    private final CellSolveListener onCellSolved;
    private final int maxCachedCells;
    private final Map<MacroCellPos, BiomeId> cellCache = new ConcurrentHashMap<>();
    private final Map<MacroCellPos, Long> lastAccessNanos = new ConcurrentHashMap<>();
    private final Object solveLock = new Object();

    public SeedMeshCache(BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed) {
        this(graph, gridConfig, worldSeed, new DecidedFaceStore(), new DecidedVertexStore());
    }

    /** Lets a caller supply its own (e.g. persisted, see this redesign's Phase 5) stores instead of fresh in-memory ones. */
    public SeedMeshCache(BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        this(graph, gridConfig, worldSeed, faceStore, vertexStore, (cell, biome, durationMillis) -> { }, Integer.MAX_VALUE);
    }

    /**
     * @param onCellSolved called exactly once EVER per cell whose answer required a real
     *                      cluster solve (never on a {@code cellCache} or fast-path hit) -
     *                      mirrors {@code SeedRegionCache.RegionSolveListener}'s contract,
     *                      one level down (single cell instead of region).
     * @param maxCachedCells defaults to unbounded ({@link Integer#MAX_VALUE}) on every
     *                       other constructor. Must be positive. Bounds only {@code
     *                       cellCache}'s live memory - {@link DecidedFaceStore}/{@link
     *                       DecidedVertexStore} (real, permanent data) unaffected; an
     *                       evicted cell is simply re-derived (fast-path lookup, not a
     *                       fresh solve) next query.
     */
    public SeedMeshCache(
        BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, DecidedFaceStore faceStore, DecidedVertexStore vertexStore,
        CellSolveListener onCellSolved, int maxCachedCells
    ) {
        if (maxCachedCells <= 0) {
            throw new IllegalArgumentException("maxCachedCells must be positive, got " + maxCachedCells);
        }
        this.graph = graph;
        this.gridConfig = gridConfig;
        this.worldSeed = worldSeed;
        this.faceStore = faceStore;
        this.vertexStore = vertexStore;
        this.onCellSolved = onCellSolved;
        this.maxCachedCells = maxCachedCells;
    }

    /** @param durationMillis wall-clock time for this one cluster solve. */
    @FunctionalInterface
    public interface CellSolveListener {
        void onSolved(MacroCellPos cell, BiomeId biome, long durationMillis);
    }

    public BiomeId biomeAt(MacroCellPos cell) {
        BiomeId cached = touchAndGetCached(cell);
        if (cached != null) {
            return cached;
        }

        double x = cellCenterX(cell);
        double z = cellCenterZ(cell);

        // Unlocked pre-check: if nothing's there, skip straight to the lock below without
        // re-checking twice. A HIT might be merely tentative (needs promoting before
        // trusted as final) or already permanent (promotion then a harmless no-op) -
        // either way, promotion is a write, done under the lock, not here.
        Optional<BiomeId> fastPath = TriangleFieldGeometryRealization.tryFastPathLookup(worldSeed, x, z, faceStore, vertexStore);

        synchronized (solveLock) {
            return biomeAtUnderLock(cell, x, z, fastPath);
        }
    }

    /** Cache read that also touches {@code lastAccessNanos} on a hit - same "get, then bump recency" pattern {@link #biomeAt} needs both before and after acquiring {@link #solveLock}. */
    private BiomeId touchAndGetCached(MacroCellPos cell) {
        BiomeId cached = cellCache.get(cell);
        if (cached != null) {
            lastAccessNanos.put(cell, System.nanoTime());
        }
        return cached;
    }

    /** Must run under {@link #solveLock} - re-checks cache/fast-path (another thread may have solved this exact cell while we waited for the lock), only falls through to a real cluster solve if genuinely nothing exists yet. */
    private BiomeId biomeAtUnderLock(MacroCellPos cell, double x, double z, Optional<BiomeId> unlockedFastPath) {
        BiomeId recheck = touchAndGetCached(cell);
        if (recheck != null) {
            return recheck;
        }

        Optional<BiomeId> fastPath = unlockedFastPath.isPresent()
            ? unlockedFastPath
            : TriangleFieldGeometryRealization.tryFastPathLookup(worldSeed, x, z, faceStore, vertexStore);
        if (fastPath.isPresent()) {
            TriangleFieldGeometryRealization.promoteCoveringFaceToPermanent(worldSeed, x, z, faceStore, vertexStore);
            return cacheAndReturn(cell, fastPath.get());
        }

        long startNanos = System.nanoTime();
        BiomeId solved = TriangleFieldGeometryRealization.realizeCluster(graph, gridConfig, worldSeed, x, z, faceStore, vertexStore);
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        onCellSolved.onSolved(cell, solved, durationMillis);

        return cacheAndReturn(cell, solved);
    }

    private BiomeId cacheAndReturn(MacroCellPos cell, BiomeId biome) {
        cellCache.put(cell, biome);
        lastAccessNanos.put(cell, System.nanoTime());
        evictIfOverCapacity();
        return biome;
    }

    private double cellCenterX(MacroCellPos cell) {
        int cellSize = gridConfig.macroCellSizeBlocks();
        return cell.x() * (double) cellSize + cellSize / 2.0;
    }

    private double cellCenterZ(MacroCellPos cell) {
        int cellSize = gridConfig.macroCellSizeBlocks();
        return cell.z() * (double) cellSize + cellSize / 2.0;
    }

    /** Only called from a context that just wrote to {@code cellCache} - eviction here is purely memory-side (real, permanent data lives in the two global stores, unaffected by this cap). */
    private void evictIfOverCapacity() {
        int excess = cellCache.size() - maxCachedCells;
        if (excess <= 0) {
            return;
        }
        lastAccessNanos.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(excess)
            .map(Map.Entry::getKey)
            .toList()
            .forEach(cell -> {
                cellCache.remove(cell);
                lastAccessNanos.remove(cell);
            });
    }
}
