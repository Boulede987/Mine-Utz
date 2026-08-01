package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * In-memory, seed-only region cache. Density function evaluation only gets
 * block coordinates (doc/ARCHITECTURE.md layer 3) — no ServerLevel, so no
 * SavedData access is possible there. Region solving is a pure function of
 * (worldSeed, region), same as all of vanilla's noise-based generation, so
 * an in-memory cache is all correctness requires; SavedData persistence
 * (used by the mod's debug command) is purely a performance/restart-cache
 * concern layered on top, not a source of truth in its own right.
 *
 * Thread-safe, including across regions: chunk generation runs on a
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
 * only ever contended on a region's first-ever solve.
 */
public final class SeedRegionCache {

    private final BiomeGraph graph;
    private final MacroGridConfig gridConfig;
    private final long worldSeed;
    private final int seedSpacingBlocks;
    private final int backtrackDepthCap;
    private final BiConsumer<RegionPos, RegionSolveResult> onRegionSolved;
    private final Map<RegionPos, RegionSolveResult> solved = new ConcurrentHashMap<>();
    private final Object solveLock = new Object();

    public SeedRegionCache(BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, int seedSpacingBlocks, int backtrackDepthCap) {
        this(graph, gridConfig, worldSeed, seedSpacingBlocks, backtrackDepthCap, (region, result) -> { });
    }

    /**
     * @param onRegionSolved called exactly once per region, right after it's
     *                       newly solved (never on a cache hit) — this
     *                       module stays framework-free on purpose (no
     *                       logging library dependency), so the mod layer
     *                       supplies its own logger here for visibility
     *                       into what's actually happening during world
     *                       generation, e.g. which region is slow or
     *                       stuck, or how often the filler fallback fires
     */
    public SeedRegionCache(
        BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, int seedSpacingBlocks, int backtrackDepthCap,
        BiConsumer<RegionPos, RegionSolveResult> onRegionSolved
    ) {
        this.graph = graph;
        this.gridConfig = gridConfig;
        this.worldSeed = worldSeed;
        this.seedSpacingBlocks = seedSpacingBlocks;
        this.backtrackDepthCap = backtrackDepthCap;
        this.onRegionSolved = onRegionSolved;
    }

    public BiomeId biomeAt(MacroCellPos cell) {
        return resultFor(gridConfig.regionOf(cell)).assignment().get(cell);
    }

    public RegionSolveResult resultFor(RegionPos region) {
        RegionSolveResult cached = solved.get(region);
        if (cached != null) {
            return cached;
        }
        // Whole solve (neighbor-boundary scan through the actual cache write) happens
        // while holding this lock, so no other region's solve can interleave with it -
        // by the time this returns, any concurrently-waiting solve for a neighboring
        // region is guaranteed to see this one already cached, not mid-solve.
        synchronized (solveLock) {
            return solved.computeIfAbsent(region, this::solve);
        }
    }

    /** Package-visible so tests can confirm a region was never force-solved as a side effect of solving a neighbor. */
    boolean isAlreadySolved(RegionPos region) {
        return solved.containsKey(region);
    }

    private RegionSolveResult solve(RegionPos region) {
        Map<MacroCellPos, BiomeId> fixedBoundary = RegionBoundaryCollector.collect(
            region, gridConfig, neighbor -> {
                RegionSolveResult neighborResult = solved.get(neighbor);
                return neighborResult == null ? null : neighborResult.assignment();
            }
        );
        RegionSolveInput input = new RegionSolveInput(graph, gridConfig, region, fixedBoundary, worldSeed, seedSpacingBlocks, backtrackDepthCap);
        RegionSolveResult result = RegionSolver.solve(input);
        onRegionSolved.accept(region, result);
        return result;
    }
}
