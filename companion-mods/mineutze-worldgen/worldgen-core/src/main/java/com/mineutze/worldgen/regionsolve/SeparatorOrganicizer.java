package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Some biome pairs have no direct adjacency rule at all (e.g. two "climate
 * clusters" that only ever touch through a shared filler biome) — wherever
 * those clusters end up spatially adjacent, {@link GapFiller} has no legal
 * choice but to fill the entire shared boundary with the one biome that's
 * legal on both sides. That boundary is already at or
 * above its own minimum size (see {@link PatchSizeEnforcer}), so nothing
 * upstream flags it — but a straight, cell-wide wall running the length of
 * the region is exactly the "ugly seam" this design set out to avoid, just
 * arrived at honestly through the graph's own rules instead of a placement
 * bug.
 *
 * This pass finds LOCAL corridor segments (see {@link #findCorridorCells})
 * and roughens their edges: for each boundary cell, deterministically (same
 * jitter technique as {@link GrowthEngine}'s organic patch outlines) tries
 * to steal one adjacent cell from whichever flanking patch is touching it.
 * A steal only happens if it stays legal, doesn't cut the donor patch into
 * two pieces, and doesn't drop the donor below its own declared minimum
 * size — the same guarantees {@link SeparatorWidener} enforces, reused
 * directly. Purely cosmetic: never runs before every correctness pass
 * above it, and any cell it can't legally claim is simply left alone.
 *
 * Detection used to gate on the cell's WHOLE connected component's
 * bounding-box aspect ratio — correct for a small, genuinely wall-shaped
 * separator patch, but wrong for a biome like plains, which can sprawl into
 * one 800+-cell component spanning nearly an entire multi-region export by
 * connecting through many regions. That component's overall aspect ratio
 * reads as roughly square (blobby elsewhere), so the old check skipped it
 * entirely — confirmed on real data (seed 42): a specific middle-region
 * stretch of that same component measured a perfectly uniform 3-4 cells
 * wide across every sampled row, a straight corridor with zero organic
 * variation, hiding inside a component that looked fine in aggregate.
 * Reported directly by the user as "these are lines, not blobs." Fixed by
 * measuring shape LOCALLY per cell via {@link AxisRunFinder} instead of
 * globally per component — see {@link #findCorridorCells}.
 */
final class SeparatorOrganicizer {

    // A corridor segment up to this many cells wide still reads as a "wall," not a
    // blob - wider than SeparatorWidthEnforcer's own hard MIN_LOCAL_WIDTH=3 floor
    // (this pass only adds organic variation on top of that, never enforces the
    // floor itself). Placeholder, tune from visual output like every other
    // grid/blend constant in this pipeline.
    private static final int CORRIDOR_WIDTH_THRESHOLD = 6;
    private static final double BULGE_PROBABILITY = 0.35;
    private static final long BULGE_SALT = 0x0BB1_6EEDL;

    private SeparatorOrganicizer() {
    }

    static void organicizeThinSeparators(
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph, long worldSeed
    ) {
        Set<MacroCellPos> corridorCells = findCorridorCells(assignment, regionCellSet, graph);
        for (Set<MacroCellPos> cluster : ConnectedComponents.find(assignment, corridorCells)) {
            bulge(cluster, assignment, regionCellSet, grid, graph, worldSeed);
        }
    }

    /**
     * A cell counts as a "corridor cell" if its bounded run (see {@link
     * AxisRunFinder}) on EITHER axis is capped on both ends by a real,
     * mutually-incompatible-with-each-other biome pair — i.e. this exact
     * spot is actually bridging two biomes that aren't allowed to touch —
     * AND that run is no wider than {@link #CORRIDOR_WIDTH_THRESHOLD}.
     * Local, not component-wide: a cell deep inside a huge blobby patch
     * elsewhere in the same component never gets flagged just because some
     * distant part of that component happens to be a corridor.
     */
    private static Set<MacroCellPos> findCorridorCells(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph) {
        Set<MacroCellPos> corridorCells = new HashSet<>();
        for (MacroCellPos cell : regionCellSet) {
            BiomeId biome = assignment.get(cell);
            if (biome == null) {
                continue;
            }
            if (isLocallyCorridorShaped(cell, biome, true, assignment, graph) || isLocallyCorridorShaped(cell, biome, false, assignment, graph)) {
                corridorCells.add(cell);
            }
        }
        return corridorCells;
    }

    private static boolean isLocallyCorridorShaped(MacroCellPos cell, BiomeId biome, boolean alongX, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph) {
        MacroCellPos low = AxisRunFinder.boundaryJustPast(cell, alongX, -1, biome, assignment);
        MacroCellPos high = AxisRunFinder.boundaryJustPast(cell, alongX, 1, biome, assignment);
        BiomeId lowBiome = assignment.get(low);
        BiomeId highBiome = assignment.get(high);
        if (lowBiome == null || highBiome == null || graph.isAllowed(lowBiome, highBiome)) {
            return false; // open-ended (edge of known data), or not actually bridging an incompatible pair
        }
        return AxisRunFinder.runLengthBetween(low, high, alongX) <= CORRIDOR_WIDTH_THRESHOLD;
    }

    private static void bulge(
        Set<MacroCellPos> component, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet,
        MacroGridConfig grid, BiomeGraph graph, long worldSeed
    ) {
        BiomeId separatorBiome = assignment.get(component.iterator().next());
        List<MacroCellPos> ordered = component.stream()
            .sorted(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z))
            .toList();

        for (MacroCellPos cell : ordered) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (!regionCellSet.contains(neighbor) || component.contains(neighbor)) {
                    continue;
                }
                BiomeId donorBiome = assignment.get(neighbor);
                if (donorBiome == null || donorBiome.equals(separatorBiome) || !shouldBulge(worldSeed, cell, neighbor)) {
                    continue;
                }
                if (!GrowthEngine.isLegal(neighbor, separatorBiome, assignment, graph)) {
                    continue;
                }
                if (!SeparatorWidener.stealingPreservesDonorConnectivity(neighbor, assignment, regionCellSet, Set.of(neighbor))) {
                    continue;
                }
                if (donorComponentSizeWithout(neighbor, donorBiome, assignment, regionCellSet) < minCellsFor(donorBiome, grid, graph)) {
                    continue;
                }
                assignment.put(neighbor, separatorBiome);
            }
        }
    }

    /** Deterministic: same (worldSeed, from, to) always decides the same way, so the result stays reproducible. */
    private static boolean shouldBulge(long worldSeed, MacroCellPos from, MacroCellPos to) {
        long hash = worldSeed ^ BULGE_SALT;
        hash = hash * 31 + from.x();
        hash = hash * 31 + from.z();
        hash = hash * 31 + to.x();
        hash = hash * 31 + to.z();
        return new Random(hash).nextDouble() < BULGE_PROBABILITY;
    }

    /** Package-visible: {@link GapFiller}'s dead-end fallback reuses this exact check when considering whether reassigning a neighbor is safe. */
    static int minCellsFor(BiomeId biome, MacroGridConfig grid, BiomeGraph graph) {
        return grid.cellCountForWidthBlocks(graph.node(biome).sizeRange().minWidthBlocks());
    }

    /** Size the donor patch would have with {@code stolen} removed — computed fresh so it reflects steals already applied earlier in this pass. Package-visible: see {@link #minCellsFor}. */
    static int donorComponentSizeWithout(
        MacroCellPos stolen, BiomeId donorBiome, Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet
    ) {
        List<MacroCellPos> starts = stolen.orthogonalNeighbors().stream()
            .filter(n -> regionCellSet.contains(n) && donorBiome.equals(assignment.get(n)))
            .toList();
        if (starts.isEmpty()) {
            return 0;
        }

        Set<MacroCellPos> visited = new HashSet<>();
        Deque<MacroCellPos> stack = new ArrayDeque<>(List.of(starts.get(0)));
        visited.add(starts.get(0));
        while (!stack.isEmpty()) {
            MacroCellPos current = stack.pop();
            for (MacroCellPos neighbor : current.orthogonalNeighbors()) {
                if (regionCellSet.contains(neighbor) && !neighbor.equals(stolen) && donorBiome.equals(assignment.get(neighbor)) && visited.add(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return visited.size();
    }
}
