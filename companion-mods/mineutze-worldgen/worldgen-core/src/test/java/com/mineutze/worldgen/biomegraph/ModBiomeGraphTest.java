package com.mineutze.worldgen.biomegraph;

import com.mineutze.worldgen.regionsolve.MacroGridConfig;
import com.mineutze.worldgen.regionsolve.RegionPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.RegionSolveResult;
import com.mineutze.worldgen.regionsolve.TriangleFieldGeometryRealization;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModBiomeGraphTest {

    /**
     * Real, deterministic, re-runnable replacement for the anecdotal justification
     * {@code BiomeGraph.MIN_LEGAL_NEIGHBORS} used to carry ("confirmed empirically... a
     * degree-3 biome still landed at zero triangles in a 178-face solve" - a remembered
     * incident involving biomes that don't even exist in this codebase's own data
     * anymore, impossible to re-check). Starvation isn't a pure graph-structure property
     * the way {@link BiomeGraph#requireEveryLocalNeighborhoodSolvable} is - it depends on
     * where {@code TriangleIdentitySizeAssignment}'s fairness-quota ordering actually
     * lands triangles on a REAL mesh, which needs real seed placement and region scale to
     * observe at all. So this test runs the real thing: the actual CSP, against a real,
     * full-scale mesh ({@link MacroGridConfig#DEFAULT}, ~262k cells), at a fixed
     * worldSeed. This test IS the proof, not a memory of one - if {@link
     * ModBiomeGraph#DEFAULT} ever starves a biome again, this fails loud and names
     * exactly which one, instead of relying on someone remembering a war story that
     * can't be checked anymore.
     */
    @Test
    void noBiomeIsStarvedOnARealProductionScaleSolve() {
        RegionSolveInput input = new RegionSolveInput(
            ModBiomeGraph.DEFAULT, MacroGridConfig.DEFAULT, new RegionPos(0, 0), Map.of(), 42L
        );

        RegionSolveResult result = TriangleFieldGeometryRealization.realize(input);

        Map<BiomeId, Integer> cellCountByBiome = new HashMap<>();
        for (BiomeId biome : result.assignment().values()) {
            cellCountByBiome.merge(biome, 1, Integer::sum);
        }

        for (BiomeNode node : ModBiomeGraph.DEFAULT.nodes()) {
            int cellCount = cellCountByBiome.getOrDefault(node.id(), 0);
            assertTrue(cellCount > 0, node.id() + " got zero cells in a real production-scale region solve (worldSeed=42) - starved");
        }
    }
}
