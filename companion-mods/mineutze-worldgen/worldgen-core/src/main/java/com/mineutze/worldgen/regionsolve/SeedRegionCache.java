package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seed-only region cache, in memory first and optionally backed by a
 * {@link RegionPersistenceStore} for anything evicted. Region solving is a
 * pure function of (worldSeed, region), same as all of vanilla's
 * noise-based generation, so re-deriving a result (from memory, from the
 * store, or — only ever once, ever — from a real solve) is always
 * correct regardless of which of those three actually answered.
 *
 * <p>{@code maxCachedRegions} bounds live memory (each solved region is a
 * real, non-trivial amount of retained state at {@link MacroGridConfig}'s
 * production cell resolution, measured directly) — anything
 * evicted is durably {@link RegionPersistenceStore#save saved} the moment
 * it's first solved, never only at eviction time, so a crash between solve
 * and eventual eviction never loses work. Both persistence and the cap are
 * opt-in: every constructor that doesn't take them defaults to {@link
 * RegionPersistenceStore#NONE} and an unbounded cap, exactly today's
 * behavior with zero code changes for any existing caller.
 *
 * <p><b>There is exactly ONE notion of "is this region solved" anywhere in
 * this class — memory, then the store, checked through the same code path
 * everywhere, including neighbor-boundary lookups mid-solve.</b> An earlier
 * design ({@code RegionCacheManager}/{@code RegionCacheData}, deleted)
 * tracked "solved" via its own independent {@code SavedData} lookup,
 * completely separate from this cache's own in-memory state — a region the
 * player had already walked through (solved here) could read as unsolved
 * there and vice versa, producing a genuinely different boundary-stitch
 * result than what had actually generated. {@link #solve} rehydrating a
 * persisted-but-evicted neighbor straight into {@link #solved} before using
 * its assignment is what makes that impossible to repeat: an evicted region
 * behaves identically to one still in memory, because by the time its data
 * is used it always IS back in memory.
 *
 * <p>Thread-safe, including across regions: chunk generation runs on a
 * worker pool, so two ADJACENT regions can genuinely be first-solved on
 * different threads at the same time. {@code ConcurrentHashMap} alone
 * only guarantees one solve per region (key) — it does nothing to stop
 * two neighboring regions from both starting their neighbor-boundary
 * scan before either has cached a result, each seeing the other as
 * "not solved yet" and proceeding unconstrained by it. That's not
 * hypothetical: it produced a real crash (forest directly touching
 * desert, no rule between them, in {@code BorderBlend}) once regions got
 * cheap enough to solve that two could race. Fixed with a single global
 * lock around the solve path (not per-region — two regions racing is
 * exactly the case that needs serializing, so a per-region lock
 * wouldn't help); {@link #resultFor} still fast-paths a plain map read
 * for the overwhelmingly common already-cached case, so the lock is
 * only ever contended on a genuine miss (a fresh solve, a store reload,
 * or a rehydration).
 */
public final class SeedRegionCache {

    private final BiomeGraph graph;
    private final MacroGridConfig gridConfig;
    private final long worldSeed;
    private final RegionSolveListener onRegionSolved;
    private final RegionPersistenceStore persistenceStore;
    private final int maxCachedRegions;
    private final Map<RegionPos, RegionSolveResult> solved = new ConcurrentHashMap<>();
    // Separate from `solved` on purpose: `resultFor`'s fast path stamps this on every
    // hit with a plain (non-atomic, benign-race-tolerant) put - approximate recency is
    // all an LRU heuristic needs, and keeping it out of `solved` itself means the fast
    // path never pays for anything beyond the one map read it already needed.
    private final Map<RegionPos, Long> lastAccessNanos = new ConcurrentHashMap<>();
    private final Object solveLock = new Object();

    public SeedRegionCache(BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed) {
        this(graph, gridConfig, worldSeed, (region, result, durationMillis) -> { });
    }

    /**
     * @param onRegionSolved called exactly once EVER per region, right after
     *                       it's freshly solved for the very first time —
     *                       never on a cache hit, never on a reload from the
     *                       persistence store (that's already-done work
     *                       coming back, not a new solve) — this module
     *                       stays framework-free on purpose (no logging
     *                       library dependency), so the mod layer supplies
     *                       its own logger here for visibility into what's
     *                       actually happening during world generation,
     *                       e.g. which region is slow or stuck, or how
     *                       often a budgeted patch gets dropped for lack of
     *                       a legal spot
     */
    public SeedRegionCache(BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, RegionSolveListener onRegionSolved) {
        this(graph, gridConfig, worldSeed, onRegionSolved, RegionPersistenceStore.NONE, Integer.MAX_VALUE);
    }

    /**
     * @param persistenceStore defaults to {@link RegionPersistenceStore#NONE} on every
     *                         other constructor — the export CLI and every existing
     *                         test want today's plain unbounded in-memory behavior,
     *                         not a real store.
     * @param maxCachedRegions defaults to unbounded ({@link Integer#MAX_VALUE}) on
     *                         every other constructor. Must be positive.
     */
    public SeedRegionCache(
        BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, RegionSolveListener onRegionSolved,
        RegionPersistenceStore persistenceStore, int maxCachedRegions
    ) {
        if (maxCachedRegions <= 0) {
            throw new IllegalArgumentException("maxCachedRegions must be positive, got " + maxCachedRegions);
        }
        this.graph = graph;
        this.gridConfig = gridConfig;
        this.worldSeed = worldSeed;
        this.onRegionSolved = onRegionSolved;
        this.persistenceStore = persistenceStore;
        this.maxCachedRegions = maxCachedRegions;
    }

    /**
     * {@code durationMillis} is wall-clock time for this one solve — the earlier
     * (and more concerning) of two possible causes for a slowdown: either this
     * one region is expensive, or many are running concurrently and contending
     * for CPU. Reported per-region so both are visible from the logs alone.
     */
    @FunctionalInterface
    public interface RegionSolveListener {
        void onSolved(RegionPos region, RegionSolveResult result, long durationMillis);
    }

    public BiomeId biomeAt(MacroCellPos cell) {
        return resultFor(gridConfig.regionOf(cell, worldSeed)).assignment().get(cell);
    }

    public RegionSolveResult resultFor(RegionPos region) {
        RegionSolveResult cached = solved.get(region);
        if (cached != null) {
            lastAccessNanos.put(region, System.nanoTime());
            return cached;
        }
        // Whole miss path (persistence-store check, real solve, eviction sweep) happens
        // while holding this lock, so no other region's solve can interleave with it -
        // by the time this returns, any concurrently-waiting solve for a neighboring
        // region is guaranteed to see this one already cached, not mid-solve. Deliberately
        // plain get/put here, not computeIfAbsent - solve() itself needs to freely
        // read/rehydrate OTHER entries of `solved` for neighbor lookups, and doing that
        // from inside a computeIfAbsent remapping function is a real ConcurrentHashMap
        // reentrancy hazard even for a different key. Holding solveLock already gives the
        // same "nothing else touches `solved` right now" guarantee without it.
        synchronized (solveLock) {
            RegionSolveResult recheck = solved.get(region);
            if (recheck != null) {
                lastAccessNanos.put(region, System.nanoTime());
                return recheck;
            }

            RegionSolveResult result = persistenceStore.load(region).orElse(null);
            if (result == null) {
                long startNanos = System.nanoTime();
                result = solve(region);
                long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
                persistenceStore.save(region, result);
                onRegionSolved.onSolved(region, result, durationMillis);
            }

            solved.put(region, result);
            lastAccessNanos.put(region, System.nanoTime());
            evictIfOverCapacity();
            return result;
        }
    }

    /** Package-visible so tests can confirm a region was never force-solved as a side effect of solving a neighbor. Checks only the in-memory map on purpose - a persisted-but-evicted region isn't "solved" from this method's narrow point of view, only from resultFor's. */
    boolean isAlreadySolved(RegionPos region) {
        return solved.containsKey(region);
    }

    /**
     * {@link TriangleFieldGeometryRealization} consults {@code fixedBoundary} for IDENTITY
     * (see its own class doc) — a region solved after its neighbors is already influenced
     * by their decisions; a region solved before them just gets an empty map here, same as
     * always, and remains free to be legality-checked by whichever of its own neighbors
     * solve later (that direction of consistency is enforced when THEY collect boundary
     * context from THIS region's already-committed result, not the other way around).
     */
    private RegionSolveResult solve(RegionPos region) {
        Map<MacroCellPos, BiomeId> fixedBoundary = RegionBoundaryCollector.collect(region, gridConfig, worldSeed, this::alreadySolvedAssignment);
        RegionSolveInput input = new RegionSolveInput(graph, gridConfig, region, fixedBoundary, worldSeed);
        return TriangleFieldGeometryRealization.realize(input);
    }

    /**
     * Only ever called from within {@link #resultFor}'s {@code synchronized}
     * block, so freely reading AND rehydrating {@link #solved} here for a
     * DIFFERENT key (a neighbor, never the region actually being solved) is
     * safe - nothing else can be touching this cache concurrently. A
     * neighbor that's solved but currently evicted gets loaded straight back
     * into {@link #solved} before its assignment is used, so it behaves
     * identically to a neighbor still in memory - see this class's own doc
     * for why that's the one property that matters here.
     */
    private Map<MacroCellPos, BiomeId> alreadySolvedAssignment(RegionPos neighbor) {
        RegionSolveResult inMemory = solved.get(neighbor);
        if (inMemory != null) {
            return inMemory.assignment();
        }
        return persistenceStore.load(neighbor).map(result -> {
            solved.put(neighbor, result);
            lastAccessNanos.put(neighbor, System.nanoTime());
            return result.assignment();
        }).orElse(null);
    }

    /** Only ever called from within resultFor's synchronized block. Already-persisted (write-through on every fresh solve) so this is purely a memory-side removal, never a data-loss risk. */
    private void evictIfOverCapacity() {
        int excess = solved.size() - maxCachedRegions;
        if (excess <= 0) {
            return;
        }
        lastAccessNanos.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .limit(excess)
            .map(Map.Entry::getKey)
            .toList()
            .forEach(region -> {
                solved.remove(region);
                lastAccessNanos.remove(region);
            });
    }
}
