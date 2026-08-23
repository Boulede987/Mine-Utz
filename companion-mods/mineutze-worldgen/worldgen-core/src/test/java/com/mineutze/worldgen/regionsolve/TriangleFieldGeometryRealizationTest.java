package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.id;
import static com.mineutze.worldgen.regionsolve.TestBiomeNodes.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangleFieldGeometryRealizationTest {

    // 1024 blocks/side - large enough to actually fit multiple full-target-sized patches
    // (this graph's biggest range tops out at 300) now that correctRadii pushes patches
    // toward their real declared size. A smaller grid (256 blocks/side) genuinely can't
    // hold more than about one correctly-sized patch, which made
    // producesRealDiversityNotASingleBiome fail once correction started actually working -
    // not a bug, just an unrealistic fixture size for what the algorithm now does. Still
    // well under MacroGridConfig.DEFAULT (2048 blocks/side, ~262k cells) for test speed.
    private static final MacroGridConfig SMALL_GRID = new MacroGridConfig(4, 256);

    private static BiomeGraph fourBiomeGraph() {
        // Bare 4-cycle (no diagonals) has a genuine dead end: {desert, savanna, plains,
        // badlands} converging simultaneously has no legal candidate (savanna-badlands is
        // never allowed) - LegalityConstrainedRasterizer correctly threw on this before the
        // desert-plains diagonal below was added, since a real Moore-neighbor convergence
        // can plausibly reach all 4 of this graph's only 4 biomes at once. Adding ONE
        // diagonal (not both) makes plains a local hub solvable for every subset without
        // making the whole graph a trivial always-legal clique - savanna-badlands stays
        // illegal, so a broken rasterizer could still be caught producing that specific
        // illegal pair.
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200), node("badlands", 150, 250)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10),
                AdjacencyRule.allow(id("plains"), id("badlands"), 10),
                AdjacencyRule.allow(id("desert"), id("badlands"), 10),
                AdjacencyRule.allow(id("desert"), id("plains"), 10)
            )
        );
    }

    private static RegionSolveInput input(long worldSeed) {
        return new RegionSolveInput(fourBiomeGraph(), SMALL_GRID, new RegionPos(0, 0), Map.of(), worldSeed);
    }

    /** The bare 4-cycle from {@link #fourBiomeGraph}'s own doc, minus the diagonal that makes it solvable - kept only for {@link #genuineDeadEndFailsLoud}. */
    private static BiomeGraph bareFourCycleGraphWithNoHub() {
        return new BiomeGraph(
            List.of(node("desert", 200, 300), node("savanna", 200, 300), node("plains", 100, 200), node("badlands", 150, 250)),
            List.of(
                AdjacencyRule.allow(id("desert"), id("savanna"), 10),
                AdjacencyRule.allow(id("savanna"), id("plains"), 10),
                AdjacencyRule.allow(id("plains"), id("badlands"), 10),
                AdjacencyRule.allow(id("desert"), id("badlands"), 10)
            )
        );
    }

    @Test
    void everyCellInTheRegionGetsAssigned() {
        RegionSolveResult result = TriangleFieldGeometryRealization.realize(input(1L));
        List<MacroCellPos> cells = SMALL_GRID.cellsInRegion(new RegionPos(0, 0), 1L);
        assertEquals(cells.size(), result.assignment().size());
        for (MacroCellPos cell : cells) {
            assertTrue(result.assignment().containsKey(cell), "cell " + cell + " has no assignment");
        }
    }

    @Test
    void sameInputProducesTheSameResult() {
        RegionSolveResult first = TriangleFieldGeometryRealization.realize(input(7L));
        RegionSolveResult second = TriangleFieldGeometryRealization.realize(input(7L));
        assertEquals(first.assignment(), second.assignment());
    }

    @Test
    void producesRealDiversityNotASingleBiome() {
        RegionSolveResult result = TriangleFieldGeometryRealization.realize(input(2026L));
        Set<BiomeId> distinct = new HashSet<>(result.assignment().values());
        assertTrue(distinct.size() > 1, "collapsed to a single biome: " + distinct);
    }

    /**
     * Was a rate threshold ({@code < 0.05}) before {@link LegalityConstrainedRasterizer}
     * existed - that framing itself implied tolerance for illegal pairs. Now a hard zero,
     * Moore (8-way) neighbors included, matching that class's own actual guarantee.
     */
    @Test
    void producesZeroIllegalAdjacencies() {
        RegionSolveResult result = TriangleFieldGeometryRealization.realize(input(2026L));
        Map<MacroCellPos, BiomeId> assignment = result.assignment();
        BiomeGraph graph = fourBiomeGraph();

        int illegal = 0;
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            for (MacroCellPos neighbor : mooreNeighbors(entry.getKey())) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !graph.isAllowed(entry.getValue(), neighborBiome)) {
                    illegal++;
                }
            }
        }
        assertEquals(0, illegal, "found an illegal Moore-adjacent biome pair in the final rasterized output");
    }

    private static List<MacroCellPos> mooreNeighbors(MacroCellPos cell) {
        List<MacroCellPos> neighbors = new java.util.ArrayList<>(cell.orthogonalNeighbors());
        neighbors.addAll(cell.diagonalNeighbors());
        return neighbors;
    }

    /**
     * The genuine dead end {@link #fourBiomeGraph}'s own doc describes, reproduced directly:
     * a bare 4-cycle graph (no diagonal, unlike the real fixture) has NO biome legal against
     * all 4 of {desert, savanna, plains, badlands} at once (savanna-badlands is never
     * allowed), and this specific worldSeed/region genuinely reaches that 4-way convergence
     * during rasterization. {@code SeamGrowthRasterizer} must fail loud here, never
     * silently place an illegal biome - matches this codebase's own established production
     * precedent (see {@code ModOverworldBiomeSource.getNoiseBiome}, which already throws
     * uncaught for a comparable "should be unreachable" case).
     */
    @Test
    void genuineDeadEndFailsLoud() {
        RegionSolveInput input = new RegionSolveInput(bareFourCycleGraphWithNoHub(), SMALL_GRID, new RegionPos(0, 0), Map.of(), 2026L);
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> TriangleFieldGeometryRealization.realize(input)
        );
        assertTrue(thrown.getMessage().contains("SeamGrowthRasterizer"), "expected a diagnostic naming the rasterizer, got: " + thrown.getMessage());
    }
}
