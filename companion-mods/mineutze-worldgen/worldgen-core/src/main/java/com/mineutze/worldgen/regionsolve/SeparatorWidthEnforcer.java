package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link SeamWidener} widens a locally-pinched separator toward an aggregate
 * cell-count target ({@code MIN_SEAM_WIDTH_CELLS}) for the whole pinch
 * cluster — proven safe and effective, but a whole-cluster total doesn't
 * guarantee every individual row/column meets it. Confirmed on real data
 * (seed 42): a plains separator forced between savanna and ocean (mutually
 * incompatible, no {@code AdjacencyRule}) ran 3-wide for most of its length
 * but narrowed to exactly 2 at several specific rows even after {@link
 * SeamWidener} already ran. This pass targets those exact rows/columns
 * directly instead of an aggregate, and also covers the width-1 case {@link
 * PinchResolver} structurally cannot fix for a required separator: giving a
 * 1-wide separator cell away to either flanking (mutually-incompatible)
 * biome would put them directly next to each other, so {@link
 * GrowthEngine#isLegal} already blocks that candidate and {@link
 * PinchResolver} correctly leaves it alone — widening is the only valid fix
 * for that shape.
 *
 * Deliberately narrower in scope than "every patch >= 3 wide everywhere":
 * ordinary round blobs naturally taper to 1-2 cells wide at their own tips,
 * and widening there would fight normal organic shape, not fix a defect.
 * Only targets a cell whose bounded run on an axis (contiguous same-biome
 * cells, capped on BOTH ends by a real, already-assigned different biome —
 * not the map edge/an unsolved neighbor) is under {@link #MIN_LOCAL_WIDTH}
 * AND the two capping biomes are mutually incompatible with EACH OTHER —
 * the same "actually bridging two incompatible neighbors" test {@link
 * SeparatorOrganicizer#organicizeThinSeparators} already uses to decide
 * whether a patch is a required separator at all. See
 * doc/Modelisation/ActivityDiagram/separator-width-enforcer.puml.
 *
 * Reuses the exact same bounded single-cell-steal safety primitives {@link
 * SeamWidener}/{@link PinchResolver} already trust: {@link
 * GrowthEngine#isLegal}, {@link
 * SeparatorWidener#stealingPreservesDonorConnectivity}, {@link
 * SeparatorOrganicizer#donorComponentSizeWithout}/{@link
 * SeparatorOrganicizer#minCellsFor}. Looped to a fixed point like the other
 * cleanup passes, {@link #MAX_PASSES} a sanity cap, never forces a widen
 * past what's legally/safely stealable.
 */
final class SeparatorWidthEnforcer {

    private static final int MIN_LOCAL_WIDTH = 3;
    private static final int MAX_PASSES = 20;

    private SeparatorWidthEnforcer() {
    }

    static void enforceMinimumSeparatorWidth(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph) {
        List<MacroCellPos> orderedCells = regionCellSet.stream()
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean anyWidened = false;
            for (MacroCellPos cell : orderedCells) {
                BiomeId biome = assignment.get(cell);
                if (biome == null) {
                    continue;
                }
                anyWidened |= widenIfThinRequiredSeparator(cell, biome, true, assignment, regionCellSet, grid, graph);
                anyWidened |= widenIfThinRequiredSeparator(cell, biome, false, assignment, regionCellSet, grid, graph);
            }
            if (!anyWidened) {
                return; // fixed point - nothing left this pass could legally/safely widen
            }
        }
    }

    /** @param alongX true = measure/widen the X axis (east/west boundary), false = the Z axis (north/south boundary). */
    private static boolean widenIfThinRequiredSeparator(
        MacroCellPos cell, BiomeId biome, boolean alongX,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        MacroCellPos low = AxisRunFinder.boundaryJustPast(cell, alongX, -1, biome, assignment);
        MacroCellPos high = AxisRunFinder.boundaryJustPast(cell, alongX, 1, biome, assignment);
        BiomeId lowBiome = assignment.get(low);
        BiomeId highBiome = assignment.get(high);
        if (lowBiome == null || highBiome == null || graph.isAllowed(lowBiome, highBiome)) {
            return false; // open-ended (edge of known data), or not actually bridging an incompatible pair
        }

        int runLength = AxisRunFinder.runLengthBetween(low, high, alongX);
        if (runLength >= MIN_LOCAL_WIDTH) {
            return false;
        }

        boolean widened = false;
        while (runLength < MIN_LOCAL_WIDTH) {
            if (tryStealBoundary(low, biome, lowBiome, assignment, regionCellSet, grid, graph)) {
                low = AxisRunFinder.stepOnce(low, alongX, -1);
            } else if (tryStealBoundary(high, biome, highBiome, assignment, regionCellSet, grid, graph)) {
                high = AxisRunFinder.stepOnce(high, alongX, 1);
            } else {
                break; // neither side legally/safely stealable right now - leave the rest as-is
            }
            widened = true;
            runLength++;
            lowBiome = assignment.get(low);
            highBiome = assignment.get(high);
        }
        return widened;
    }

    private static boolean tryStealBoundary(
        MacroCellPos boundaryCell, BiomeId separatorBiome, BiomeId donorBiome,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        if (!regionCellSet.contains(boundaryCell)) {
            return false; // never reassign a fixed boundary cell from an already-solved neighbor region
        }
        if (!GrowthEngine.isLegal(boundaryCell, separatorBiome, assignment, graph)) {
            return false;
        }
        if (!SeparatorWidener.stealingPreservesDonorConnectivity(boundaryCell, assignment, regionCellSet, Set.of())) {
            return false;
        }
        int minCells = SeparatorOrganicizer.minCellsFor(donorBiome, grid, graph);
        if (SeparatorOrganicizer.donorComponentSizeWithout(boundaryCell, donorBiome, assignment, regionCellSet) < minCells) {
            return false;
        }

        assignment.put(boundaryCell, separatorBiome);
        return true;
    }
}
