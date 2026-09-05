package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The store-aware, GRID-FREE replacement for {@code SeedRegionCache} (see
 * doc/decisions/2026-08-29-remove-region-stitching.md and this whole redesign's own plan
 * doc). There is no fixed-size window or partition anywhere in this class — the CSP
 * batching unit is an ORGANIC CLUSTER, grown outward from wherever a query actually
 * lands until it closes against already-decided territory (see {@link
 * TriangleFieldGeometryRealization#realizeCluster}'s own doc). This is what fixes the
 * real, observed failure mode a fixed-size window had: a face straddling a window
 * boundary got a throwaway, never-reconciled guess from whichever window solved first,
 * producing a visible straight seam exactly on the window's own edge. A cluster's own
 * boundary is never arbitrary — it's exactly "everything this solve actually decided,"
 * so there's no leftover territory for a later solve to disagree about.
 *
 * <p><b>Three-tier lookup, cheapest first:</b>
 * <ol>
 *   <li>{@code cellCache} — a plain per-cell answer, once known, is permanent (a cell's
 *   biome never changes once decided) — the common case for a REPEATED query at the
 *   same or a recently-seen cell costs a single map read, no mesh, no lock.</li>
 *   <li>{@link TriangleFieldGeometryRealization#tryFastPathLookup} — a new cell whose
 *   covering face already has SOME value (tentative or permanent — see {@link
 *   DecidedFaceStore}'s own two-tier doc) from an EARLIER, different query nearby still
 *   costs only a small local mesh + a real point-in-polygon test, no CSP. A HIT here is
 *   checked unlocked first, but promoting it (see below) is a write, done under the lock.</li>
 *   <li>{@link TriangleFieldGeometryRealization#realizeCluster} — only a genuinely new
 *   area (nothing decided nearby at all) pays for an actual cluster growth + CSP solve,
 *   under the single global lock below.</li>
 * </ol>
 *
 * <p><b>Promotion</b> — whichever tier answers a query, the exact moment that answer is
 * about to reach a real caller (Minecraft is about to place real terrain with it), the
 * covering face (and any of its own vertex triples now fully settled) is promoted to
 * PERMANENT via {@link TriangleFieldGeometryRealization#promoteCoveringFaceToPermanent}/
 * {@code realizeCluster}'s own internal promotion — irrevocable from that point on. Real
 * gameplay (structure-search ring probes especially — see this whole redesign's own plan
 * doc for why that specific pattern matters) is the only thing that can ever lock a face
 * in; everything else a cluster solve merely touches as context stays tentative, freely
 * revisable by a later solve that needs to.
 *
 * <p>Concurrency: same single-global-lock discipline {@code SeedRegionCache} already
 * established — a lock-free read for {@code cellCache} and the initial fast-path
 * pre-check (both only ever read the already-thread-safe {@link DecidedFaceStore}/{@link
 * DecidedVertexStore}), one lock serializing promotion and the whole slow-path
 * solve-then-commit sequence so two adjacent queries can never race each other into a
 * stale view of one another.
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

    /** Lets a caller supply its own (e.g. persisted, see this redesign's own Phase 5) stores instead of fresh in-memory ones. */
    public SeedMeshCache(BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, DecidedFaceStore faceStore, DecidedVertexStore vertexStore) {
        this(graph, gridConfig, worldSeed, faceStore, vertexStore, (cell, biome, durationMillis) -> { }, Integer.MAX_VALUE);
    }

    /**
     * @param onCellSolved called exactly once EVER per cell whose answer required an
     *                      actual cluster solve (never on a {@code cellCache} or fast-path
     *                      hit) — mirrors {@code SeedRegionCache.RegionSolveListener}'s own
     *                      contract, one level down (a single cell instead of a region).
     * @param maxCachedCells defaults to unbounded ({@link Integer#MAX_VALUE}) on every
     *                       other constructor. Must be positive. Bounds only {@code
     *                       cellCache}'s own live memory — {@link DecidedFaceStore}/{@link
     *                       DecidedVertexStore} (the real, permanent data) are unaffected
     *                       by this cap; an evicted cell is simply re-derived (fast-path
     *                       lookup, not a fresh solve) the next time it's queried.
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
        BiomeId cached = cellCache.get(cell);
        if (cached != null) {
            lastAccessNanos.put(cell, System.nanoTime());
            return cached;
        }

        double x = cellCenterX(cell);
        double z = cellCenterZ(cell);

        // Unlocked pre-check: if nothing's there yet, skip straight to the lock below
        // without wasting time re-checking twice. A HIT here might be merely tentative
        // (needs promoting before we can trust it as a real, final answer) or already
        // permanent (promotion is then a harmless no-op) - either way, promotion itself
        // is a write, so it happens under the lock, not here.
        Optional<BiomeId> fastPath = TriangleFieldGeometryRealization.tryFastPathLookup(worldSeed, x, z, faceStore, vertexStore);

        synchronized (solveLock) {
            BiomeId recheck = cellCache.get(cell);
            if (recheck != null) {
                lastAccessNanos.put(cell, System.nanoTime());
                return recheck;
            }

            Optional<BiomeId> recheckFastPath = fastPath.isPresent()
                ? fastPath
                : TriangleFieldGeometryRealization.tryFastPathLookup(worldSeed, x, z, faceStore, vertexStore);
            if (recheckFastPath.isPresent()) {
                TriangleFieldGeometryRealization.promoteCoveringFaceToPermanent(worldSeed, x, z, faceStore, vertexStore);
                return cacheAndReturn(cell, recheckFastPath.get());
            }

            long startNanos = System.nanoTime();
            BiomeId solved = TriangleFieldGeometryRealization.realizeCluster(graph, gridConfig, worldSeed, x, z, faceStore, vertexStore);
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
            onCellSolved.onSolved(cell, solved, durationMillis);

            return cacheAndReturn(cell, solved);
        }
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

    /** Only ever called from a context that just wrote to {@code cellCache} - evicting here is purely a memory-side removal (see this class's own doc: the real, permanent data lives in the two global stores, unaffected by this cap). */
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
