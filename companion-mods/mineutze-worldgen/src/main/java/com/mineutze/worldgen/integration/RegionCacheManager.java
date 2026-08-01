package com.mineutze.worldgen.integration;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionBoundaryCollector;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.RegionSolver;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Solves regions on demand and caches each in its own {@link SavedData},
 * a sibling of vanilla's region/poi/entities folders (doc/ARCHITECTURE.md
 * "Cache storage"). Boundary context comes only from {@link RegionBoundaryCollector}
 * — never force-solves a neighbor region just to get it.
 *
 * This is the ServerLevel-backed path (debug command, anything else that
 * has a live level). Density function evaluation has no ServerLevel, so it
 * goes through {@link com.mineutze.worldgen.regionsolve.SeedRegionCache}
 * instead — same algorithm, in-memory only.
 */
public final class RegionCacheManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RegionCacheManager() {
    }

    public static Map<MacroCellPos, BiomeId> getOrSolve(ServerLevel level, RegionPos region) {
        MacroGridConfig grid = MacroGridConfig.DEFAULT;
        RegionCacheData data = level.getDataStorage().computeIfAbsent(factoryFor(level, region, grid), saveName(region));
        return data.assignment();
    }

    private static SavedData.Factory<RegionCacheData> factoryFor(ServerLevel level, RegionPos region, MacroGridConfig grid) {
        return new SavedData.Factory<>(() -> solveRegion(level, region, grid), RegionCacheData::load);
    }

    private static RegionCacheData solveRegion(ServerLevel level, RegionPos region, MacroGridConfig grid) {
        Map<MacroCellPos, BiomeId> fixedBoundary = RegionBoundaryCollector.collect(
            region, grid, neighbor -> {
                RegionCacheData neighborData = level.getDataStorage().get(factoryFor(level, neighbor, grid), saveName(neighbor));
                return neighborData == null ? null : neighborData.assignment();
            }
        );
        RegionSolveInput input = new RegionSolveInput(ModBiomeGraph.DEFAULT, grid, region, fixedBoundary, level.getSeed());

        RegionSolveResult result = RegionSolver.solve(input);
        for (String warning : result.warnings()) {
            LOGGER.warn("[mineutze_worldgen] region {},{}: {}", region.x(), region.z(), warning);
        }
        return new RegionCacheData(result.assignment());
    }

    private static String saveName(RegionPos region) {
        return "mineutze_worldgen_region_" + region.x() + "_" + region.z();
    }
}
