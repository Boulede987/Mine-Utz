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
 *
 * First version passed an empty repulsion set (plain centroid-distance
 * growth) — measured on a real export: it widened the targeted pair fine,
 * but growth could drift toward a THIRD, unrelated incompatible biome
 * instead, making a different pair's approach distance worse (forest/
 * badlands and ocean/badlands both went from 2 cells apart to 1). Now
 * passes {@link SeparatorWidener#widen}'s {@code repelFrom} as the union of
 * every incompatible-pair member found across the WHOLE cluster's pinch
 * detection, not just the one pair that flagged any single cell — growth
 * steers away from everything locally relevant at once.
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
            Set<BiomeId> repulsionBiomes = incompatibleBiomesNear(cluster, assignment, graph);
            Set<MacroCellPos> repelFrom = cellsOfBiomes(assignment, repulsionBiomes);
            SeparatorWidener.widen(cluster, biome, MIN_SEAM_WIDTH_CELLS, assignment, regionCellSet, graph, repelFrom);
        }
    }

    private static Set<MacroCellPos> findPinchCells(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph) {
        Set<MacroCellPos> pinch = new HashSet<>();
        for (MacroCellPos cell : regionCellSet) {
            if (!incompatiblePairsNear(cell, assignment, graph).isEmpty()) {
                pinch.add(cell);
            }
        }
        return pinch;
    }

    /**
     * Every biome (from anywhere in the cluster's own pinch detection, not just
     * whichever single cell/pair was found first) that must be widened away from —
     * the actual fix for the measured regression: undirected widening previously
     * only knew about the ONE pair that triggered a given cell, so growth could
     * still drift toward a THIRD, unrelated incompatible biome nearby. Unioning
     * every incompatible-pair member detected across the whole cluster means
     * nothing locally relevant is left out of what growth steers away from.
     */
    private static Set<BiomeId> incompatibleBiomesNear(Set<MacroCellPos> cluster, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        Set<BiomeId> repulsion = new HashSet<>();
        for (MacroCellPos cell : cluster) {
            for (UnorderedBiomePair pair : incompatiblePairsNear(cell, assignment, graph)) {
                repulsion.add(pair.a());
                repulsion.add(pair.b());
            }
        }
        return repulsion;
    }

    private static Set<MacroCellPos> cellsOfBiomes(Map<MacroCellPos, BiomeId> assignment, Set<BiomeId> biomes) {
        Set<MacroCellPos> cells = new HashSet<>();
        for (Map.Entry<MacroCellPos, BiomeId> entry : assignment.entrySet()) {
            if (biomes.contains(entry.getValue())) {
                cells.add(entry.getKey());
            }
        }
        return cells;
    }

    private record UnorderedBiomePair(BiomeId a, BiomeId b) {
    }

    /** Every mutually-incompatible pair, both members different from this cell's own biome, found within PINCH_RADIUS of it. */
    private static Set<UnorderedBiomePair> incompatiblePairsNear(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        BiomeId ownBiome = assignment.get(cell);
        if (ownBiome == null) {
            return Set.of();
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

        Set<UnorderedBiomePair> pairs = new HashSet<>();
        for (BiomeId a : nearbyBiomes) {
            for (BiomeId b : nearbyBiomes) {
                if (!a.equals(b) && !graph.isAllowed(a, b)) {
                    pairs.add(a.compareTo(b) <= 0 ? new UnorderedBiomePair(a, b) : new UnorderedBiomePair(b, a));
                }
            }
        }
        return pairs;
    }
}
