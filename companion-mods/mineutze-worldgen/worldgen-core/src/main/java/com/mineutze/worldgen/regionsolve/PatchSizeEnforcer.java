package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adjacency legality alone doesn't guarantee a biome's own declared size
 * range — gap-fill's majority-vote can leave an undersized patch of any
 * biome, filler included. Two genuinely different situations produce that:
 * <ul>
 *   <li>The patch has a legal neighbor to merge into — it wasn't actually
 *       needed there, so absorb it and move on.
 *   <li>No neighbor can legally absorb it — which means it's bridging two
 *       biomes that aren't allowed to touch each other (a "required
 *       separator": removing it would put them in direct contact). It
 *       still has to respect its own size range, so {@link SeparatorWidener}
 *       grows it into its flanking patches instead of merging it away —
 *       DESIGN.md's adjacency prohibitions were never meant to produce a
 *       1-cell token seam standing in for a real buffer.
 * </ul>
 * Universal-filler patches are NOT exempt from either path — filler is a
 * normal, legitimately-placeable biome with its own size range, and an
 * undersized filler separator is exactly as visible a workaround as an
 * undersized anything-else separator.
 *
 * <p>Public so the export CLI's own whole-swath cleanup ({@code
 * RegionGridSolver}) can also call this directly, over the FULL combined
 * export rather than one region's own cells — a per-region call can miss an
 * undersized patch entirely if its true bordering neighbors belong to
 * regions that hadn't solved yet (found via direct user report: an 8-cell
 * ocean patch, entirely surrounded by 3 different not-yet-solved neighbor
 * regions at solve time, against ocean's real 600-1000 block/~88-cell
 * minimum — the exact same order-dependent blind spot {@link
 * PixelFragmentRepair}'s own doc describes for illegal adjacency and small
 * fragments, just for patch SIZE instead).
 *
 * <p>{@code assignment} may carry read-only context cells beyond {@code
 * regionCellSet} (e.g. {@code fixedBoundaryCells}, already merged in by the
 * caller) — {@link ConnectedComponents#find} measures a component's TRUE
 * size across all of it, so a patch that continues into an already-solved
 * neighbor doesn't read as spuriously undersized. Only cells actually in
 * {@code regionCellSet} are ever written back to; a merge into a different
 * biome filters to that set before mutating, and {@link SeparatorWidener}
 * already only ever claims cells within it. Fixes a real bug found when this
 * class still ran per-region as part of {@code SeedRegionCache}'s own live
 * solve (before this session's growth rewrite moved that live path to
 * {@code SeamGrowthRasterizer}, which doesn't call this class at all — see
 * this class's own current callers, {@code RegionGridSolver} being the only
 * one, export-only): measuring size using ONLY {@code regionCellSet} could
 * spuriously merge or steal cells from a patch that was actually fine once
 * its neighbor-side portion was counted. {@code
 * PatchSizeEnforcerTest.measuresComponentSizeAcrossBoundaryContextWithoutMutatingIt}
 * keeps this behavior real and checked even though today's one real caller
 * happens to pass equal sets.
 *
 * <p>Loops to a fixed point ({@link #MAX_PASSES} sanity cap) rather than a
 * single pass — merging one undersized component into a neighbor can itself
 * leave a FRESH undersized, disconnected remnant behind (e.g. two small
 * components of different biomes both merge into the same third biome on
 * the same pass; the result isn't necessarily one connected patch). A
 * single pass never re-examines what its own merges just produced. Found
 * the same way as the paragraph above, same now-obsolete live wiring: a
 * 5-cell savanna component stranded under savanna's own 61-cell minimum
 * survived a single pass, and only read as fine in export CLI runs because
 * {@code RegionGridSolver}'s own whole-swath cleanup happened to run this
 * same merge logic a second time afterward.
 */
public final class PatchSizeEnforcer {

    // Sanity cap on merge/widen passes, same fixed-point-loop-cap role as this
    // pipeline's other repair passes - bounds the search, not a correctness
    // mechanism.
    private static final int MAX_PASSES = 20;

    private PatchSizeEnforcer() {
    }

    /**
     * @param pass 0-indexed pass number, {@code componentCount}/{@code undersizedCount}/
     *             {@code mergeCount}/{@code widenCount} this pass's own counts —
     *             ad-hoc summary for the {@code MINEUTZE_WORLDGEN_TRACE}
     *             investigation into why a region boxed in by real neighbor
     *             context can cost far more than an isolated region's own
     *             baseline (see {@code RegionSolveStageTimingSpikeTest}'s
     *             3x3-grid finding) — not a permanent metrics API.
     */
    private record PassResult(int componentCount, int undersizedCount, int mergeCount, int widenCount, boolean changedAny) {
    }

    public static void enforceMinimumPatchSize(
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        SeparatorWidener.STATS.reset();
        long start = System.nanoTime();
        int passesRun = 0;
        int totalComponents = 0;
        int totalUndersized = 0;
        int totalMerges = 0;
        int totalWidens = 0;

        for (int pass = 0; pass < MAX_PASSES; pass++) {
            PassResult result = absorbUndersizedComponentsOnce(assignment, regionCellSet, grid, graph);
            passesRun++;
            totalComponents += result.componentCount();
            totalUndersized += result.undersizedCount();
            totalMerges += result.mergeCount();
            totalWidens += result.widenCount();
            if (!result.changedAny()) {
                break;
            }
        }

        WorldgenTrace.log("PatchSizeEnforcer summary: passes=" + passesRun + " totalComponentsScanned=" + totalComponents
            + " totalUndersized=" + totalUndersized + " merges=" + totalMerges + " widens=" + totalWidens
            + " regionCellSetSize=" + regionCellSet.size() + " assignmentSize=" + assignment.size()
            + " wallMs=" + ((System.nanoTime() - start) / 1_000_000.0) + " " + SeparatorWidener.STATS);
    }

    private static PassResult absorbUndersizedComponentsOnce(
        Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, MacroGridConfig grid, BiomeGraph graph
    ) {
        List<Set<MacroCellPos>> components = ConnectedComponents.find(assignment, regionCellSet);
        Map<MacroCellPos, Integer> patchSizeByCell = new HashMap<>();
        for (Set<MacroCellPos> component : components) {
            component.forEach(cell -> patchSizeByCell.put(cell, component.size()));
        }

        boolean changedAny = false;
        int undersizedCount = 0;
        int mergeCount = 0;
        int widenCount = 0;
        for (Set<MacroCellPos> component : components) {
            BiomeId biome = assignment.get(component.iterator().next());
            BiomeNode node = graph.node(biome);
            int minCells = grid.cellCountForWidthBlocks(node.sizeRange().minWidthBlocks());
            if (component.size() >= minCells) {
                continue;
            }
            undersizedCount++;

            Optional<BiomeId> mergeTarget = bestMergeTarget(component, biome, assignment, patchSizeByCell, graph);
            if (mergeTarget.isPresent()) {
                component.stream().filter(regionCellSet::contains).forEach(cell -> assignment.put(cell, mergeTarget.get()));
                mergeCount++;
            } else {
                SeparatorWidener.widen(component, biome, minCells, assignment, regionCellSet, graph);
                widenCount++;
            }
            changedAny = true;
        }
        return new PassResult(components.size(), undersizedCount, mergeCount, widenCount, changedAny);
    }

    /** Largest (by existing patch size) neighboring biome that stays legal across every cell in the component if adopted. */
    private static Optional<BiomeId> bestMergeTarget(
        Set<MacroCellPos> component, BiomeId ownBiome, Map<MacroCellPos, BiomeId> assignment,
        Map<MacroCellPos, Integer> patchSizeByCell, BiomeGraph graph
    ) {
        Map<BiomeId, Integer> candidatePatchSize = new HashMap<>();
        for (MacroCellPos cell : component) {
            for (MacroCellPos neighbor : cell.orthogonalNeighbors()) {
                if (component.contains(neighbor)) {
                    continue;
                }
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !neighborBiome.equals(ownBiome)) {
                    candidatePatchSize.merge(neighborBiome, patchSizeByCell.getOrDefault(neighbor, 0), Math::max);
                }
            }
        }

        return candidatePatchSize.keySet().stream()
            .filter(candidate -> isLegalForWholeComponent(component, candidate, assignment, graph))
            .max(Comparator.<BiomeId>comparingInt(candidatePatchSize::get).thenComparing(Comparator.naturalOrder()));
    }

    /**
     * Legal (Moore, both orthogonal and diagonal) against every neighbor outside the
     * component itself — see {@link CellPlacementRules#isLegal} for the shared
     * implementation. Was orthogonal-only until this delegated to that shared check -
     * a real, silent diagonal-illegal-adjacency gap, not just a style difference.
     */
    private static boolean isLegalForWholeComponent(
        Set<MacroCellPos> component, BiomeId candidate, Map<MacroCellPos, BiomeId> assignment, BiomeGraph graph
    ) {
        return CellPlacementRules.isLegalForGroup(component, candidate, assignment, graph);
    }
}
