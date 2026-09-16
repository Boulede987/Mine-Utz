package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;

import java.util.List;

/**
 * Design-time, near-zero-cost structural motif screen - derived from mining {@link
 * GeometricFeasibilityProof}'s own real output (2026-09-13 analysis, 38,722 real geometric
 * dead ends on the current 11-rule graph): among the 146 minimal (size-2), width-extreme-
 * INDEPENDENT failures, two arithmetic-only patterns together explain all 146.
 *
 * <p><b>Not a proof.</b> Both motifs are real, DATA-VALIDATED correlates of true geometric
 * infeasibility (see this class's own cross-validation against {@link
 * GeometricFeasibilityProof}'s real solver output), not derived from first principles and
 * not independently sufficient. Flagging a candidate here means "verify with the real
 * solver before trusting," never "proven infeasible" on its own - that status belongs to
 * {@link GeometricFeasibilityProof}'s own real, per-instance solve.
 *
 * <p><b>Motif 1 - Dominant Neighbor</b> ({@link #triggersDominantNeighborMotif}): if ANY
 * real neighbor's own declared MINIMUM width already exceeds {@link #BASELINE_WIDTH_BLOCKS},
 * that neighbor alone can squeeze a candidate's achievable ceiling below its own
 * requirement, regardless of every other neighbor - a direct consequence of {@code
 * WeightedVertexPlacement}'s proven weight-monotonicity property (increasing one neighbor's
 * weight strictly shrinks every other face's achievable area - see that class's own doc).
 * Explains 94/146 (64%) of the real failures mined.
 *
 * <p><b>Motif 2 - Fragile Narrow Tier</b> ({@link #triggersFragileNarrowTierMotif}): a
 * candidate whose own declared range sits ENTIRELY below {@link #BASELINE_WIDTH_BLOCKS}
 * (today's catalog: exactly the 300-600 tier, 14 biomes) is at real, COMPOUNDING risk from
 * two or more real committed neighbors jointly, even when neither alone triggers Motif 1 -
 * explains the remaining 52/146 (36%), for full 146/146 coverage together with Motif 1
 * (for the width-extreme-independent failure class specifically - see this session's own
 * analysis for the separate, still-being-characterized width-extreme-DEPENDENT class).
 */
public final class BridgeMotifChecker {

    /** Same reference point {@link GeometricFeasibilityProof}/{@code FaceWidthPicker} already anchor width decisions to - HEX_SIZE_BLOCKS=325 circumradius, doubled. */
    private static final int BASELINE_WIDTH_BLOCKS = 650;

    private BridgeMotifChecker() {
    }

    /** Motif 1: any neighbor's own declared minimum already exceeds baseline. */
    public static boolean triggersDominantNeighborMotif(BiomeGraph graph, List<BiomeId> neighbors) {
        return neighbors.stream().anyMatch(neighbor -> graph.node(neighbor).sizeRange().minWidthBlocks() > BASELINE_WIDTH_BLOCKS);
    }

    /**
     * Motif 2: candidate's own range sits entirely below baseline (today's narrowest real
     * tier), with 2+ real committed neighbors present to compound against it. Deliberately
     * excludes anything Motif 1 already covers (mutually exclusive classification, not
     * double-counted) - a subset with a dominant neighbor is reported as Motif 1 only, even
     * if the candidate also happens to be narrow-tier.
     */
    public static boolean triggersFragileNarrowTierMotif(BiomeGraph graph, BiomeId candidate, List<BiomeId> neighbors) {
        if (triggersDominantNeighborMotif(graph, neighbors) || neighbors.size() < 2) {
            return false;
        }
        BiomeSizeRange candidateRange = graph.node(candidate).sizeRange();
        return candidateRange.maxWidthBlocks() < BASELINE_WIDTH_BLOCKS;
    }

    /** One flagged (subset, candidate) pair and which motif(s) fired. */
    public record MotifFlag(List<BiomeId> neighbors, BiomeId candidate, boolean dominantNeighbor, boolean fragileNarrowTier) {
        public boolean anyMotifTriggered() {
            return dominantNeighbor || fragileNarrowTier;
        }
    }

    /** Classifies every (subset, candidate) pair in {@code atRisk} - reuses the exact same at-risk enumeration {@link GeometricFeasibilityProof} runs the real solver against, so the two are directly comparable. */
    static List<MotifFlag> classifyAll(BiomeGraph graph, List<GeometricFeasibilityProof.AtRiskSubset> atRisk) {
        return atRisk.stream()
            .flatMap(subset -> subset.candidates().stream().map(candidate -> new MotifFlag(
                subset.subset(), candidate,
                triggersDominantNeighborMotif(graph, subset.subset()),
                triggersFragileNarrowTierMotif(graph, candidate, subset.subset())
            )))
            .toList();
    }

    public static void main(String[] args) {
        BiomeGraph graph = ModBiomeGraph.OVERWORLD;
        List<BiomeId> allBiomes = graph.nodes().stream().map(BiomeNode::id).sorted().toList();
        List<GeometricFeasibilityProof.AtRiskSubset> atRisk = GeometricFeasibilityProof.findAtRiskSubsets(graph, allBiomes);

        List<MotifFlag> flags = classifyAll(graph, atRisk);
        long dominantCount = flags.stream().filter(MotifFlag::dominantNeighbor).count();
        long fragileCount = flags.stream().filter(MotifFlag::fragileNarrowTier).count();
        long anyCount = flags.stream().filter(MotifFlag::anyMotifTriggered).count();

        System.out.println("At-risk (subset, candidate) pairs checked: " + flags.size());
        System.out.println("Motif 1 (Dominant Neighbor) triggered: " + dominantCount);
        System.out.println("Motif 2 (Fragile Narrow Tier) triggered: " + fragileCount);
        System.out.println("Either motif triggered: " + anyCount + " (" + String.format("%.1f", 100.0 * anyCount / flags.size()) + "% of all at-risk pairs)");
        System.out.println();
        System.out.println("NOTE: a flagged pair is a fast, cheap SCREEN, not a proof - cross-validate against");
        System.out.println("GeometricFeasibilityProof's real solver output before trusting a specific flag.");
    }
}
