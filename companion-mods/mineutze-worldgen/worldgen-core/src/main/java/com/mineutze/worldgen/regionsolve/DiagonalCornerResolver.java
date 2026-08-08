package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nothing in this pipeline ever checked diagonals — {@link GrowthEngine#isLegal}
 * and {@link GapFiller} both only ever look at the 4 orthogonal neighbors, so
 * two mutually-incompatible biomes (no {@code AdjacencyRule}, e.g. desert and
 * forest) can end up touching at a single corner ({@code d./.f}) completely
 * undetected. Confirmed on real data (seed 42, `validate_biome_export.py`'s
 * new diagonal check): 6 real corner collisions, including that exact
 * desert/forest pattern.
 *
 * A corner-only touch (A at (x,z), B at (x+1,z+1), with NEITHER off-diagonal
 * cell equal to A or B — i.e. no orthogonal contact either, which {@link
 * GrowthEngine#isLegal} already prevents) can't be fixed by changing the
 * other two corners: A and B are still diagonally adjacent no matter what's
 * between them. The only fix is to change A or B itself. See
 * doc/Modelisation/ActivityDiagram/diagonal-corner-resolver.puml.
 *
 * Runs after {@link PinchResolver}, reusing the exact same bounded
 * single-cell-reassignment safety primitives {@link GapFiller#makeLegalHere}
 * already trusts: {@link GrowthEngine#isLegal} for full-neighborhood
 * legality (plus an extra check that the new biome is actually compatible
 * with the diagonal neighbor too, or it would just recreate the same
 * collision under a different name), {@link
 * SeparatorWidener#stealingPreservesDonorConnectivity} so the cell's current
 * patch doesn't get split in two, and {@link
 * SeparatorOrganicizer#donorComponentSizeWithout}/{@link
 * SeparatorOrganicizer#minCellsFor} so it doesn't drop below its own
 * declared minimum size. Never forces a resolution — a collision neither
 * cell can legally/safely give away is simply left alone.
 *
 * Looped to a fixed point (same technique {@link PatchOpener}/{@link
 * PinchResolver} already use): a reassignment can turn up a new collision
 * elsewhere. {@link #MAX_PASSES} a sanity cap on top.
 */
final class DiagonalCornerResolver {

    private static final int MAX_PASSES = 20;
    private static final List<int[]> DIAGONAL_OFFSETS = List.of(new int[]{1, 1}, new int[]{1, -1});

    private DiagonalCornerResolver() {
    }

    static void resolveDiagonalCollisions(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph) {
        List<MacroCellPos> orderedCells = regionCellSet.stream()
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();

        int previousViolationCount = Integer.MAX_VALUE;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            List<MacroCellPos[]> violations = findDiagonalViolations(orderedCells, assignment, graph);
            if (violations.isEmpty() || violations.size() >= previousViolationCount) {
                return;
            }
            previousViolationCount = violations.size();
            for (MacroCellPos[] pair : violations) {
                resolveOne(pair[0], pair[1], assignment, regionCellSet, grid, graph);
            }
        }
    }

    private static List<MacroCellPos[]> findDiagonalViolations(List<MacroCellPos> orderedCells, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        List<MacroCellPos[]> violations = new ArrayList<>();
        for (MacroCellPos cell : orderedCells) {
            BiomeId biome = assignment.get(cell);
            if (biome == null) {
                continue;
            }
            for (int[] offset : DIAGONAL_OFFSETS) {
                MacroCellPos diagonal = new MacroCellPos(cell.x() + offset[0], cell.z() + offset[1]);
                BiomeId diagonalBiome = assignment.get(diagonal);
                if (diagonalBiome == null || biome.equals(diagonalBiome) || graph.isAllowed(biome, diagonalBiome)) {
                    continue;
                }
                if (touchesOrthogonallyToo(cell, offset, biome, diagonalBiome, assignment)) {
                    continue; // GrowthEngine.isLegal already prevents this pair from being an orthogonal violation
                }
                violations.add(new MacroCellPos[]{cell, diagonal});
            }
        }
        return violations;
    }

    private static boolean touchesOrthogonallyToo(MacroCellPos cell, int[] offset, BiomeId biome, BiomeId diagonalBiome, Map<MacroCellPos, BiomeId> assignment) {
        BiomeId off1 = assignment.get(new MacroCellPos(cell.x() + offset[0], cell.z()));
        BiomeId off2 = assignment.get(new MacroCellPos(cell.x(), cell.z() + offset[1]));
        return biome.equals(off1) || diagonalBiome.equals(off1) || biome.equals(off2) || diagonalBiome.equals(off2);
    }

    private static void resolveOne(MacroCellPos cellA, MacroCellPos cellB, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph) {
        BiomeId biomeA = assignment.get(cellA);
        BiomeId biomeB = assignment.get(cellB);
        if (biomeA == null || biomeB == null || biomeA.equals(biomeB) || graph.isAllowed(biomeA, biomeB)) {
            return; // already resolved earlier this pass
        }
        if (tryGiveAway(cellA, biomeB, assignment, regionCellSet, grid, graph)) {
            return;
        }
        tryGiveAway(cellB, biomeA, assignment, regionCellSet, grid, graph);
    }

    private static boolean tryGiveAway(
        MacroCellPos cell, BiomeId diagonalBiome,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        BiomeId currentBiome = assignment.get(cell);
        Set<BiomeId> candidates = new LinkedHashSet<>();
        for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
            BiomeId neighborBiome = assignment.get(neighbor);
            if (neighborBiome != null && !neighborBiome.equals(currentBiome)) {
                candidates.add(neighborBiome);
            }
        }

        for (BiomeId candidate : candidates) {
            if (!graph.isAllowed(candidate, diagonalBiome)) {
                continue; // would just recreate the same collision under a different biome
            }
            if (!GrowthEngine.isLegal(cell, candidate, assignment, graph)) {
                continue;
            }
            if (!SeparatorWidener.stealingPreservesDonorConnectivity(cell, assignment, regionCellSet, Set.of())) {
                continue;
            }
            int minCells = SeparatorOrganicizer.minCellsFor(currentBiome, grid, graph);
            if (SeparatorOrganicizer.donorComponentSizeWithout(cell, currentBiome, assignment, regionCellSet) < minCells) {
                continue;
            }

            assignment.put(cell, candidate);
            return true;
        }
        return false;
    }
}
