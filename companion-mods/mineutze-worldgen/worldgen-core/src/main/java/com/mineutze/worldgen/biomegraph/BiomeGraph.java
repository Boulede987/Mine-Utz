package com.mineutze.worldgen.biomegraph;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static config: biome families (nodes) and their adjacency rules (edges).
 * See doc/ARCHITECTURE.md layer 1.
 *
 * Validated eagerly at construction (fail loud on a bad config, not lazily
 * during world gen): every rule must reference known nodes, no duplicate
 * rule per pair, and at least one "universal filler" node must be allowed
 * next to every other node. That last invariant is what guarantees the
 * region solver's backtracking always terminates without relaxing a
 * constraint (doc/ARCHITECTURE.md "Decided").
 */
public final class BiomeGraph {

    private final Map<BiomeId, BiomeNode> nodesById;
    private final Map<UnorderedBiomePair, AdjacencyRule> rulesByPair;

    public BiomeGraph(Collection<BiomeNode> nodes, Collection<AdjacencyRule> rules) {
        this.nodesById = indexNodes(nodes);
        this.rulesByPair = indexRules(rules);
        requireAllRuleNodesKnown();
        requireUniversalFillerExists();
    }

    private static Map<BiomeId, BiomeNode> indexNodes(Collection<BiomeNode> nodes) {
        Map<BiomeId, BiomeNode> indexed = new HashMap<>();
        for (BiomeNode node : nodes) {
            if (indexed.put(node.id(), node) != null) {
                throw new IllegalArgumentException("Duplicate BiomeNode id: " + node.id());
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<UnorderedBiomePair, AdjacencyRule> indexRules(Collection<AdjacencyRule> rules) {
        Map<UnorderedBiomePair, AdjacencyRule> indexed = new HashMap<>();
        for (AdjacencyRule rule : rules) {
            if (indexed.put(rule.pair(), rule) != null) {
                throw new IllegalArgumentException(
                    "Duplicate AdjacencyRule for pair: " + rule.biomeA() + " / " + rule.biomeB());
            }
        }
        return Map.copyOf(indexed);
    }

    private void requireAllRuleNodesKnown() {
        for (UnorderedBiomePair pair : rulesByPair.keySet()) {
            requireKnownNode(pair.first());
            requireKnownNode(pair.second());
        }
    }

    private void requireKnownNode(BiomeId id) {
        if (!nodesById.containsKey(id)) {
            throw new IllegalArgumentException("AdjacencyRule references unknown biome node: " + id);
        }
    }

    private void requireUniversalFillerExists() {
        List<BiomeId> fillers = nodesById.values().stream()
            .filter(BiomeNode::universalFiller)
            .map(BiomeNode::id)
            .toList();
        if (fillers.isEmpty()) {
            throw new IllegalArgumentException(
                "BiomeGraph has no universalFiller node — backtracking would have no guaranteed legal fallback");
        }
        for (BiomeId filler : fillers) {
            requireFillerAllowedNextToEveryOtherNode(filler);
        }
    }

    private void requireFillerAllowedNextToEveryOtherNode(BiomeId filler) {
        for (BiomeId other : nodesById.keySet()) {
            if (!other.equals(filler) && !isAllowed(filler, other)) {
                throw new IllegalArgumentException(
                    "universalFiller node " + filler + " must be allowed next to every other node, "
                        + "but is missing/forbidden next to " + other);
            }
        }
    }

    public BiomeNode node(BiomeId id) {
        BiomeNode node = nodesById.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown biome node: " + id);
        }
        return node;
    }

    public Collection<BiomeNode> nodes() {
        return nodesById.values();
    }

    /** Same biome is always allowed next to itself; otherwise default-deny. */
    public boolean isAllowed(BiomeId a, BiomeId b) {
        return a.equals(b) || rulesByPair.containsKey(UnorderedBiomePair.of(a, b));
    }

    public AdjacencyRule rule(BiomeId a, BiomeId b) {
        AdjacencyRule rule = rulesByPair.get(UnorderedBiomePair.of(a, b));
        if (rule == null) {
            throw new IllegalArgumentException("No AdjacencyRule between " + a + " and " + b + " (not allowed)");
        }
        return rule;
    }

    public Set<BiomeId> universalFillers() {
        Set<BiomeId> fillers = new HashSet<>();
        for (BiomeNode node : nodesById.values()) {
            if (node.universalFiller()) {
                fillers.add(node.id());
            }
        }
        return Set.copyOf(fillers);
    }
}
