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
 * {@link PatchOpener}'s "is this cell part of a full 2x2 same-biome square"
 * erosion test is directionally incomplete: a cell can belong to a genuine
 * 2x2 square along ONE axis (so erosion never flags it) while still being
 * exactly 1-wide along the OTHER axis — a tab sticking sideways off a solid
 * block, backed by 3+ Moore-neighbors from that block alone. Confirmed on
 * real data (seed 42, macro-cell (-24,-24)): a savanna cell backed by a
 * solid savanna block to the north, pinched directly east/west between
 * plains and desert. Erosion correctly flags this exact cell as non-core
 * every single pass (no 2x2 square touches it), but re-dilation
 * ({@link PatchOpener}'s stricter-cohesion {@link GapFiller#fillGaps})
 * reclaims it right back via the same Moore-neighbor-count check, satisfied
 * entirely by the solid block behind it with zero awareness of the
 * perpendicular axis — erode and reclaim repeat identically forever, and
 * {@link PatchOpener}'s fixed-point loop just stops (no progress != no
 * pinch). See doc/Modelisation/ActivityDiagram/pinch-resolver.puml.
 *
 * Runs after the other 4 cleanup passes, and does the opposite of erosion:
 * instead of removing the cell and hoping re-dilation does better, it asks
 * directly whether one of the two biomes actually pinching the cell can
 * legally and safely absorb it instead — same {@link BiomeId} used by
 * validate_biome_export.py's own pinch definition (both neighbors on one
 * axis differ, cell still has same-biome neighbors on the other axis, so
 * not an isolated cell). Reuses the exact safety primitives {@link
 * GapFiller#makeLegalHere} already trusts for a bounded single-cell
 * reassignment: {@link GrowthEngine#isLegal} against every neighbor, {@link
 * SeparatorWidener#stealingPreservesDonorConnectivity} so the cell's current
 * patch doesn't get split in two, and {@link
 * SeparatorOrganicizer#donorComponentSizeWithout}/{@link
 * SeparatorOrganicizer#minCellsFor} so it doesn't drop the current patch
 * below its own declared minimum size. Never forces a resolution — a pinch
 * neither flanking biome can legally/safely absorb is simply left alone,
 * same "never force" rule as every other pass in this pipeline.
 *
 * Looped to a fixed point (same technique {@link PatchOpener} already
 * uses): a single reassignment can turn up a NEW pinch on the cell's other
 * axis, or ripple into the donor patch's own shape, so one sweep isn't
 * always the end of the story. Stops the moment the pinch count stops
 * strictly decreasing, {@link #MAX_PASSES} a sanity cap on top.
 */
final class PinchResolver {

    private static final int MAX_PASSES = 20;

    private PinchResolver() {
    }

    static void resolvePinchPoints(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph) {
        List<MacroCellPos> orderedCells = regionCellSet.stream()
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();

        int previousPinchCount = Integer.MAX_VALUE;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            List<MacroCellPos> pinchedCells = findPinchedCells(orderedCells, assignment);
            if (pinchedCells.isEmpty() || pinchedCells.size() >= previousPinchCount) {
                return;
            }
            previousPinchCount = pinchedCells.size();
            for (MacroCellPos cell : pinchedCells) {
                resolveOne(cell, assignment, regionCellSet, grid, graph);
            }
        }
    }

    private static List<MacroCellPos> findPinchedCells(List<MacroCellPos> orderedCells, Map<MacroCellPos, BiomeId> assignment) {
        List<MacroCellPos> pinched = new ArrayList<>();
        for (MacroCellPos cell : orderedCells) {
            BiomeId biome = assignment.get(cell);
            if (biome != null && !flankingBiomesIfPinched(cell, biome, assignment).isEmpty()) {
                pinched.add(cell);
            }
        }
        return pinched;
    }

    /** Re-reads the cell fresh (an earlier steal this same pass may have already resolved or reshaped it) rather than trusting the scan that queued it. */
    private static void resolveOne(MacroCellPos cell, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph) {
        BiomeId biome = assignment.get(cell);
        if (biome == null) {
            return;
        }
        Set<BiomeId> candidates = flankingBiomesIfPinched(cell, biome, assignment);
        for (BiomeId candidate : candidates) {
            if (tryReassign(cell, biome, candidate, assignment, regionCellSet, grid, graph)) {
                return;
            }
        }
    }

    /**
     * Mirrors validate_biome_export.py's find_pinch_points exactly: a cell is pinched
     * along an axis if BOTH neighbors on that axis differ from its own biome. A cell
     * pinched on BOTH axes at once is isolated (all 4 orthogonal neighbors differ) -
     * not this pass's job, so it returns empty rather than a candidate set. The
     * flanking biomes returned are only the pinched axis's own neighbors (null/missing
     * neighbors, e.g. at a region's outer edge, are simply not offered as candidates).
     */
    private static Set<BiomeId> flankingBiomesIfPinched(MacroCellPos cell, BiomeId biome, Map<MacroCellPos, BiomeId> assignment) {
        BiomeId north = assignment.get(new MacroCellPos(cell.x(), cell.z() - 1));
        BiomeId south = assignment.get(new MacroCellPos(cell.x(), cell.z() + 1));
        BiomeId east = assignment.get(new MacroCellPos(cell.x() + 1, cell.z()));
        BiomeId west = assignment.get(new MacroCellPos(cell.x() - 1, cell.z()));

        boolean verticalPinch = !biome.equals(north) && !biome.equals(south);
        boolean horizontalPinch = !biome.equals(east) && !biome.equals(west);
        if (verticalPinch == horizontalPinch) {
            return Set.of(); // neither pinched, or isolated (both axes pinched at once)
        }

        Set<BiomeId> candidates = new LinkedHashSet<>();
        if (verticalPinch) {
            addIfPresent(candidates, north);
            addIfPresent(candidates, south);
        } else {
            addIfPresent(candidates, east);
            addIfPresent(candidates, west);
        }
        return candidates;
    }

    private static void addIfPresent(Set<BiomeId> candidates, BiomeId biome) {
        if (biome != null) {
            candidates.add(biome);
        }
    }

    private static boolean tryReassign(
        MacroCellPos cell, BiomeId currentBiome, BiomeId candidate,
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        if (!GrowthEngine.isLegal(cell, candidate, assignment, graph)) {
            return false;
        }
        if (!SeparatorWidener.stealingPreservesDonorConnectivity(cell, assignment, regionCellSet, Set.of())) {
            return false;
        }
        int minCells = SeparatorOrganicizer.minCellsFor(currentBiome, grid, graph);
        if (SeparatorOrganicizer.donorComponentSizeWithout(cell, currentBiome, assignment, regionCellSet) < minCells) {
            return false;
        }

        assignment.put(cell, candidate);
        return true;
    }
}
