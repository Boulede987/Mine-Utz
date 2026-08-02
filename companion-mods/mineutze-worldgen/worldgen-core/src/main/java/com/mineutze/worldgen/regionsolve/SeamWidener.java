package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link PatchOpener} and {@link PatchSizeEnforcer}/{@link SeparatorWidener}
 * both reason about ONE patch's own shape/size — neither one catches a
 * different failure mode: a giant, otherwise-legitimately-sized buffer
 * biome (typically filler) can still have one LOCAL spot pinched down to a
 * sliver where it happens to be squeezed between two mutually-incompatible
 * neighbors, because component-level size/shape checks only ever see the
 * component's TOTAL extent. A sprawling plains component covering a third
 * of a region easily clears its own minimum size and `PatchOpener`'s
 * "am I part of a chunky blob of MY OWN biome" check — both are blind to
 * what's happening on the OTHER side, right at the specific cells where
 * badlands and ocean happen to come close together through it (a real
 * reported case: 1-2 cell wide plains strip separating them despite each
 * pinch cell individually having 3+ Moore same-biome neighbors from an
 * unrelated nearby solid mass).
 *
 * Finds every cell whose small neighborhood contains two mutually-
 * incompatible biomes neither of which is that cell's own — a "pinch"
 * cell — groups same-biome pinch cells into local clusters (reusing
 * {@link ConnectedComponents} against just that subset, not the whole
 * region), and widens each cluster with {@link SeparatorWidener#widen},
 * exactly the same steal-cells/preserve-legality/preserve-donor-
 * connectivity mechanism {@link PatchSizeEnforcer} already uses for
 * whole undersized separator patches — just re-targeted at a local
 * pinch instead of an entire component.
 */
final class SeamWidener {

    // Chebyshev radius of the window checked around each cell for an incompatible
    // pair - bounds "pinch" detection to genuinely tight squeezes, not "these two
    // biomes both exist somewhere in this region." Placeholder, tune from output.
    private static final int PINCH_RADIUS = 2;

    // Target local bulk for a pinch cluster once widened - a flat cell count, not
    // derived from any biome's own declared size range (this is about LOCAL width
    // at a specific pinch, unrelated to a patch's overall minimum). Placeholder.
    private static final int MIN_SEAM_WIDTH_CELLS = 9;

    private SeamWidener() {
    }

    static void widenNarrowSeams(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph) {
        Set<MacroCellPos> pinchCells = findPinchCells(assignment, regionCellSet, graph);
        List<Set<MacroCellPos>> clusters = ConnectedComponents.find(assignment, pinchCells);

        for (Set<MacroCellPos> cluster : clusters) {
            BiomeId biome = assignment.get(cluster.iterator().next());
            SeparatorWidener.widen(cluster, biome, MIN_SEAM_WIDTH_CELLS, assignment, regionCellSet, graph);
        }
    }

    private static Set<MacroCellPos> findPinchCells(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph) {
        Set<MacroCellPos> pinch = new HashSet<>();
        for (MacroCellPos cell : regionCellSet) {
            if (isPinchCell(cell, assignment, graph)) {
                pinch.add(cell);
            }
        }
        return pinch;
    }

    /** True if two mutually-incompatible biomes, both different from this cell's own, both appear within PINCH_RADIUS of it. */
    private static boolean isPinchCell(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        BiomeId ownBiome = assignment.get(cell);
        if (ownBiome == null) {
            return false;
        }

        Set<BiomeId> nearbyBiomes = new HashSet<>();
        for (int dx = -PINCH_RADIUS; dx <= PINCH_RADIUS; dx++) {
            for (int dz = -PINCH_RADIUS; dz <= PINCH_RADIUS; dz++) {
                BiomeId biome = assignment.get(new MacroCellPos(cell.x() + dx, cell.z() + dz));
                if (biome != null && !biome.equals(ownBiome)) {
                    nearbyBiomes.add(biome);
                }
            }
        }

        for (BiomeId a : nearbyBiomes) {
            for (BiomeId b : nearbyBiomes) {
                if (!a.equals(b) && !graph.isAllowed(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }
}
