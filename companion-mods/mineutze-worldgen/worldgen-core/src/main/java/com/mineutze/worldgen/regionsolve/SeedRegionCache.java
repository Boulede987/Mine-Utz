package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, seed-only region cache. Density function evaluation only gets
 * block coordinates (doc/ARCHITECTURE.md layer 3) — no ServerLevel, so no
 * SavedData access is possible there. Region solving is a pure function of
 * (worldSeed, region), same as all of vanilla's noise-based generation, so
 * an in-memory cache is all correctness requires; SavedData persistence
 * (used by the mod's debug command) is purely a performance/restart-cache
 * concern layered on top, not a source of truth in its own right.
 *
 * Thread-safe: {@link ConcurrentHashMap#computeIfAbsent} only ever reads
 * (never solves) neighboring regions while computing one, so it can't
 * recursively re-enter computation for the same map.
 */
public final class SeedRegionCache {

    private final BiomeGraph graph;
    private final MacroGridConfig gridConfig;
    private final long worldSeed;
    private final int seedSpacingBlocks;
    private final int backtrackDepthCap;
    private final Map<RegionPos, RegionSolveResult> solved = new ConcurrentHashMap<>();

    public SeedRegionCache(BiomeGraph graph, MacroGridConfig gridConfig, long worldSeed, int seedSpacingBlocks, int backtrackDepthCap) {
        this.graph = graph;
        this.gridConfig = gridConfig;
        this.worldSeed = worldSeed;
        this.seedSpacingBlocks = seedSpacingBlocks;
        this.backtrackDepthCap = backtrackDepthCap;
    }

    public BiomeId biomeAt(MacroCellPos cell) {
        return resultFor(gridConfig.regionOf(cell)).assignment().get(cell);
    }

    public RegionSolveResult resultFor(RegionPos region) {
        return solved.computeIfAbsent(region, this::solve);
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
        return RegionSolver.solve(input);
    }
}
