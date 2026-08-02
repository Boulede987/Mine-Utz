package com.mineutze.worldgen.regionsolve;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.GrowthCohesionProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link GrowthEngine}/{@link GapFiller}'s cohesion check (Moore-neighborhood
 * same-biome count) blocks a truly isolated 1-wide bridge, but not a "ridge"
 * that's 1-wide in one axis while flanked by mass on one side — that shape
 * can score just as high on a raw neighbor count as ordinary blob-boundary
 * growth does, because the count is direction-blind (found via a real
 * in-game example: a forest tendril with Moore-count 4, well above the
 * minSameBiomeNeighbors=2 threshold, that still read as an obvious thin
 * corridor). Raising the threshold doesn't cleanly fix it either — the same
 * tendril would need threshold 5 to block, which also blocks plenty of
 * ordinary growth (typically scores 3).
 *
 * Morphological "opening" (erode then dilate) instead: a cell only survives
 * erosion if it belongs to at least one fully-same-biome 2x2 square — a
 * 1-wide line, by construction, never completes one anywhere along its
 * length, no matter how much diagonal flanking support it has. Dilation is
 * just {@link GapFiller#fillGaps} run again on the eroded assignment: the
 * surviving cores re-expand through the exact same legal/cohesive
 * nearest-patch growth gap-fill already does, and a spot that was a tendril
 * fails the same cohesion check again post-erosion (its support was erased
 * too), so it won't simply regrow the same shape.
 *
 * Gated per-biome: skipped for any biome whose {@code minSameBiomeNeighbors}
 * is below {@link #MIN_COHESION_TO_OPEN} — a biome deliberately configured
 * to allow thin shapes (e.g. {@code UNRESTRICTED}) has that as its actual
 * identity, not a defect opening should erase.
 *
 * Looped to a fixed point, not a single pass — measured on real output
 * (seed 42, 9-region swath) that one pass only trimmed 122 spur cells down
 * to 114: re-dilation reclaims via the WEAKER Moore-neighbor-count check
 * ({@link GrowthEngine#satisfiesCohesion}), not the stronger 2x2-completion
 * check ({@link #isCore}) this class enforces, so a spur often regrows a
 * short stub of itself that still isn't core. Repeating erode-then-dilate
 * keeps trimming that stub back on the next pass. Stops the moment the spur
 * count stops strictly decreasing (not just when it hits zero) — some spurs
 * settle into a stable 2-state cycle (erode, get reclaimed identically,
 * erode again forever) rather than a diminishing one, and re-detecting "no
 * improvement over last pass" catches that without an infinite loop.
 * {@link #MAX_PASSES} is a sanity cap on top of that, same role as
 * {@code BudgetPlanner.MAX_DRAWS} — not expected to ever bind given the
 * strictly-decreasing check already terminates first in every case tried.
 *
 * Re-dilation uses a {@link #stricterCohesion stricter-cohesion} view of the
 * graph (min neighbors +1 for every opened biome), not the biome's own
 * normal profile — confirmed against real game data that a bare-minimum
 * reclaim (exactly {@link #MIN_COHESION_TO_OPEN} Moore-neighbors: one
 * orthogonal contact plus its one diagonal, nothing more) regrows exactly
 * the kind of short dangling finger this class exists to remove, even
 * though it isn't a full 2x2 and even though erosion correctly identified
 * it as a spur in the first place. A confirmed round blob boundary normally
 * scores 3 Moore-neighbors on re-dilation (see
 * {@link GrowthEngine#satisfiesCohesion}'s own doc for the worked example),
 * so requiring one more than the opened threshold
 * blocks the bare-minimum reattachment without blocking ordinary regrowth.
 * A cell too weakly connected to clear that bar simply stays unclaimed and
 * falls to {@link GapFiller}'s own filler fallback — same "never force"
 * behavior as everywhere else in this pipeline, not a special case.
 */
final class PatchOpener {

    private static final int MIN_COHESION_TO_OPEN = 2;
    private static final int MAX_PASSES = 50;
    private static final int REDILATION_EXTRA_COHESION = 1;

    private PatchOpener() {
    }

    /**
     * Requires {@code assignment} to already cover every cell in {@code regionCellSet}
     * (i.e. run after {@link GapFiller#fillGaps}, never before) - when a pass finds no
     * spurs it returns immediately without calling {@link GapFiller} at all, so it does
     * NOT independently guarantee full coverage the way {@code GapFiller} itself does.
     * Caught by a test that skipped the GapFiller pre-step and got a NullPointerException
     * from an unassigned cell.
     */
    static void openThinProtrusions(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph, long worldSeed) {
        BiomeGraph redilationGraph = stricterCohesion(graph);
        int previousSpurCount = Integer.MAX_VALUE;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            Set<MacroCellPos> spurs = findSpurs(assignment, regionCellSet, graph);
            if (spurs.isEmpty() || spurs.size() >= previousSpurCount) {
                return;
            }
            previousSpurCount = spurs.size();
            spurs.forEach(assignment::remove);
            GapFiller.fillGaps(assignment, regionCellSet, redilationGraph, worldSeed);
        }
    }

    /**
     * Same nodes/rules as {@code graph}, but every opened biome's cohesion profile
     * requires {@link #REDILATION_EXTRA_COHESION} more Moore-neighbors than usual.
     * A biome exempt from opening (below {@link #MIN_COHESION_TO_OPEN}) is left
     * exactly as-is - it never had spurs removed in the first place, so nothing
     * about it should reclaim any differently than normal {@link GapFiller} would.
     */
    private static BiomeGraph stricterCohesion(BiomeGraph graph) {
        List<BiomeId> ids = graph.nodes().stream().map(BiomeNode::id).sorted().toList();

        List<BiomeNode> stricterNodes = new ArrayList<>();
        for (BiomeId id : ids) {
            BiomeNode node = graph.node(id);
            GrowthCohesionProfile cohesion = node.cohesionProfile();
            GrowthCohesionProfile stricter = cohesion.minSameBiomeNeighbors() < MIN_COHESION_TO_OPEN
                ? cohesion
                : new GrowthCohesionProfile(
                    Math.min(8, cohesion.minSameBiomeNeighbors() + REDILATION_EXTRA_COHESION),
                    cohesion.directionalRunMajor(), cohesion.directionalRunMinor()
                );
            stricterNodes.add(new BiomeNode(node.id(), node.sizeRange(), node.shapeHints(), node.featureToggles(), node.universalFiller(), stricter));
        }

        List<AdjacencyRule> rules = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (graph.isAllowed(ids.get(i), ids.get(j))) {
                    rules.add(graph.rule(ids.get(i), ids.get(j)));
                }
            }
        }

        return new BiomeGraph(stricterNodes, rules);
    }

    private static Set<MacroCellPos> findSpurs(Map<MacroCellPos, BiomeId> assignment, Set<MacroCellPos> regionCellSet, BiomeGraph graph) {
        Set<MacroCellPos> spurs = new HashSet<>();
        for (MacroCellPos cell : regionCellSet) {
            BiomeId biome = assignment.get(cell);
            if (biome == null) {
                continue;
            }
            if (graph.node(biome).cohesionProfile().minSameBiomeNeighbors() < MIN_COHESION_TO_OPEN) {
                continue;
            }
            if (!isCore(cell, biome, assignment)) {
                spurs.add(cell);
            }
        }
        return spurs;
    }

    /** True if {@code cell} belongs to at least one fully-same-biome 2x2 square. */
    private static boolean isCore(MacroCellPos cell, BiomeId biome, Map<MacroCellPos, BiomeId> assignment) {
        return isFullSquare(cell, 0, 0, biome, assignment)
            || isFullSquare(cell, -1, 0, biome, assignment)
            || isFullSquare(cell, 0, -1, biome, assignment)
            || isFullSquare(cell, -1, -1, biome, assignment);
    }

    /** The 2x2 square whose lowest-coordinate corner is at {@code cell} offset by ({@code dx}, {@code dz}) - true only if all 4 members are {@code biome}. */
    private static boolean isFullSquare(MacroCellPos cell, int dx, int dz, BiomeId biome, Map<MacroCellPos, BiomeId> assignment) {
        int x = cell.x() + dx;
        int z = cell.z() + dz;
        return biome.equals(assignment.get(new MacroCellPos(x, z)))
            && biome.equals(assignment.get(new MacroCellPos(x + 1, z)))
            && biome.equals(assignment.get(new MacroCellPos(x, z + 1)))
            && biome.equals(assignment.get(new MacroCellPos(x + 1, z + 1)));
    }
}
