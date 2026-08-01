package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Smoothly blends a per-biome scalar value near macro-cell borders, using
 * each specific adjacent pair's own {@code AdjacencyRule.blendWidthBlocks}
 * (doc/ARCHITECTURE.md: "mountains-plains gets a wide blend... desert-
 * savanna can be narrow" — a property of the relation, not a global
 * constant). Bilinear across the up to 4 cells touching a point (center,
 * horizontal neighbor, vertical neighbor, diagonal) so corners blend too,
 * not just straight edges.
 *
 * Pure/stateless: the caller supplies how to look up a cell's biome and
 * how to turn a biome into the value being blended (e.g. a density
 * offset), so this has no Minecraft dependency and no opinion on what
 * the value represents.
 */
public final class BorderBlend {

    private BorderBlend() {
    }

    public static double blendedValue(
        int blockX, int blockZ, MacroGridConfig grid, BiomeGraph graph,
        Function<MacroCellPos, BiomeId> biomeAt, ToDoubleFunction<BiomeId> valueOf
    ) {
        int cellSize = grid.macroCellSizeBlocks();
        int cellX = Math.floorDiv(blockX, cellSize);
        int cellZ = Math.floorDiv(blockZ, cellSize);
        int localX = blockX - cellX * cellSize;
        int localZ = blockZ - cellZ * cellSize;

        BiomeId centerBiome = biomeAt.apply(new MacroCellPos(cellX, cellZ));
        int xNeighborCellX = localX < cellSize / 2 ? cellX - 1 : cellX + 1;
        int zNeighborCellZ = localZ < cellSize / 2 ? cellZ - 1 : cellZ + 1;

        BiomeId xNeighborBiome = biomeAt.apply(new MacroCellPos(xNeighborCellX, cellZ));
        BiomeId zNeighborBiome = biomeAt.apply(new MacroCellPos(cellX, zNeighborCellZ));
        BiomeId diagonalBiome = biomeAt.apply(new MacroCellPos(xNeighborCellX, zNeighborCellZ));

        double tX = blendFactor(localX, xNeighborCellX - cellX, cellSize, centerBiome, xNeighborBiome, graph);
        double tZ = blendFactor(localZ, zNeighborCellZ - cellZ, cellSize, centerBiome, zNeighborBiome, graph);

        double nearRow = lerp(valueOf.applyAsDouble(centerBiome), valueOf.applyAsDouble(xNeighborBiome), tX);
        double farRow = lerp(valueOf.applyAsDouble(zNeighborBiome), valueOf.applyAsDouble(diagonalBiome), tX);
        return lerp(nearRow, farRow, tZ);
    }

    /**
     * 0 = fully centerBiome, 1 = fully neighborBiome; only nonzero within
     * that specific pair's own blend width of the shared border.
     * Orthogonally-adjacent cells are supposed to always be a legal
     * (rule-bearing) pair by construction — but that guarantee is only as
     * good as the region solver's own invariants, and it has been wrong
     * once already (a since-fixed cross-region solve race let a forest
     * cell end up directly touching a desert one, no rule between them,
     * which crashed world gen when this called {@code graph.rule(...)}
     * unconditionally). Treating "not actually allowed" as "no blend"
     * rather than trusting the precondition means a bug here degrades to
     * a visible seam, never a crash.
     */
    private static double blendFactor(int local, int neighborDirection, int cellSize, BiomeId centerBiome, BiomeId neighborBiome, BiomeGraph graph) {
        if (!graph.isAllowed(centerBiome, neighborBiome)) {
            return 0.0;
        }
        if (centerBiome.equals(neighborBiome)) {
            return 0.0;
        }
        int distanceToBorder = neighborDirection < 0 ? local : cellSize - 1 - local;
        int blendWidth = graph.rule(centerBiome, neighborBiome).blendWidthBlocks();
        if (blendWidth <= 0 || distanceToBorder >= blendWidth) {
            return 0.0;
        }
        return 1.0 - (double) distanceToBorder / blendWidth;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
