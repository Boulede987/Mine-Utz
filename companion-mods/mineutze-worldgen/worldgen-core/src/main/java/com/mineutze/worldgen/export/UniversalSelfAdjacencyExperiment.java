package com.mineutze.worldgen.export;

import com.mineutze.worldgen.biomegraph.AdjacencyRule;
import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.biomegraph.ModBiomeGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * One-off experiment (not wired into any production path): what if EVERY biome could be
 * self-adjacent, not just the 5 given {@code selfAdjacencyLimit=1} today (see {@code
 * ModBiomeGraph}'s desert + deep-ocean family comment)? Reconstructs {@code
 * ModBiomeGraph.OVERWORLD} with every node's {@code selfAdjacencyLimit} raised to 1,
 * identical rule set otherwise, and re-runs the SAME identity-level completeness check
 * ({@link BiomeGraph#findMinimalUnsolvablePartialNeighborhoods}) real vs. hypothetical,
 * side by side. Answers whether the self-adjacency ban is a real structural contributor to
 * the scarce-candidate problem, or a red herring.
 */
public final class UniversalSelfAdjacencyExperiment {

    private static final int DEFAULT_BLEND_WIDTH_BLOCKS = 10;
    private static final int MAX_NEIGHBORS = 6;

    private UniversalSelfAdjacencyExperiment() {
    }

    public static void main(String[] args) {
        BiomeGraph real = ModBiomeGraph.OVERWORLD;
        BiomeGraph universalSelfAdjacency = withUniversalSelfAdjacency(real);

        System.out.println("Real graph: " + real.nodes().size() + " biomes.");
        System.out.println("Computing identity-level minimal unsolvable partial neighborhoods (size <= "
            + (MAX_NEIGHBORS - 1) + ") for BOTH graphs - this reuses the exact same exhaustive check "
            + "BiomeGraphCompletenessReport already relies on...");

        List<List<BiomeId>> realConflicts = real.findMinimalUnsolvablePartialNeighborhoods(MAX_NEIGHBORS);
        List<List<BiomeId>> hypotheticalConflicts = universalSelfAdjacency.findMinimalUnsolvablePartialNeighborhoods(MAX_NEIGHBORS);

        System.out.println();
        System.out.println("REAL graph (today's actual selfAdjacencyLimit values): " + realConflicts.size() + " minimal unsolvable partial neighborhoods.");
        System.out.println("HYPOTHETICAL graph (every biome selfAdjacencyLimit=1): " + hypotheticalConflicts.size() + " minimal unsolvable partial neighborhoods.");

        long resolvedByUniversalSelfAdjacency = realConflicts.stream().filter(conflict -> !stillConflictAfter(conflict, hypotheticalConflicts)).count();
        System.out.println();
        System.out.println(resolvedByUniversalSelfAdjacency + " of the real graph's " + realConflicts.size()
            + " conflicts are RESOLVED (no longer a minimal unsolvable subset, nor covered by a smaller one) under universal self-adjacency.");

        long newConflicts = hypotheticalConflicts.stream().filter(conflict -> !stillConflictAfter(conflict, realConflicts)).count();
        System.out.println(newConflicts + " NEW conflicts appear under universal self-adjacency that didn't exist in the real graph.");

        int sizeTwoReal = countBySize(realConflicts, 2);
        int sizeTwoHypothetical = countBySize(hypotheticalConflicts, 2);
        System.out.println();
        System.out.println("Size-2 (pure-pair) conflicts - the most reliable signal: real=" + sizeTwoReal + ", universal-self-adjacency=" + sizeTwoHypothetical);
    }

    /** Whether {@code conflict} (from the OTHER graph's result) is still a real conflict against {@code otherGraphConflicts} - either present directly, or a superset of something in it (monotonic: more constraints never un-break a conflict). */
    private static boolean stillConflictAfter(List<BiomeId> conflict, List<List<BiomeId>> otherGraphConflicts) {
        return otherGraphConflicts.stream().anyMatch(other -> new java.util.HashSet<>(conflict).containsAll(other));
    }

    private static int countBySize(List<List<BiomeId>> conflicts, int size) {
        return (int) conflicts.stream().filter(conflict -> conflict.size() == size).count();
    }

    /** Package-private (not private) so {@code UniversalSelfAdjacencyGeometricExperiment} reuses this exact same reconstruction instead of duplicating it. */
    static BiomeGraph withUniversalSelfAdjacency(BiomeGraph source) {
        List<BiomeId> ids = source.nodes().stream().map(BiomeNode::id).sorted().toList();

        List<BiomeNode> modifiedNodes = new ArrayList<>();
        for (BiomeNode node : source.nodes()) {
            modifiedNodes.add(new BiomeNode(node.id(), node.sizeRange(), Math.max(1, node.selfAdjacencyLimit())));
        }

        List<AdjacencyRule> reconstructedRules = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (source.isAllowed(ids.get(i), ids.get(j))) {
                    reconstructedRules.add(AdjacencyRule.allow(ids.get(i), ids.get(j), DEFAULT_BLEND_WIDTH_BLOCKS));
                }
            }
        }

        return BiomeGraph.forProduction(modifiedNodes, reconstructedRules);
    }
}
