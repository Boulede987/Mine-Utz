package com.mineutze.worldgen.biomegraph;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Static config: biome families (nodes) + adjacency rules (edges).
 *
 * Validated eagerly at construction (fail loud on bad config, not lazily during
 * world gen): every rule must reference known nodes, no duplicate rule per pair.
 *
 * No node ever universally legal. Cell with no legal biome given neighbors = graph
 * misconfiguration (e.g. 3 mutually-incompatible biomes meeting at a point) - fails
 * loud in the pattern solver. See DECISIONS.md 2026-08-07.
 */
public final class BiomeGraph {

    private final Map<BiomeId, BiomeNode> nodesById;
    private final Map<UnorderedBiomePair, AdjacencyRule> rulesByPair;

    // Cheap heuristic floor, not a derived guarantee - whether a graph solves is the solve check's job.
    // NOT enforced in constructor - some unit tests deliberately build narrow chain/bridge
    // graphs (degree 1-2); real graphs go through forProduction, which enforces this.
    private static final int MIN_LEGAL_NEIGHBORS = 3;

    public BiomeGraph(Collection<BiomeNode> nodes, Collection<AdjacencyRule> rules) {
        this.nodesById = indexNodes(nodes);
        this.rulesByPair = indexRules(rules);
        requireAllRuleNodesKnown();
    }

    /**
     * The one entry point a real, shippable biome set should build through - applies
     * {@link #requireMinLegalNeighbors()} so production graph can't skip it. Bare
     * constructor stays public: ~half this test suite deliberately builds small narrow-
     * degree graphs (chains, hubs, 3-node triangles) to isolate one algorithm behavior -
     * those fixtures don't care about (and often can't meet, e.g. 3-node graph can never
     * reach degree 3) the fairness/coverage concern this factory's check protects.
     */
    public static BiomeGraph forProduction(Collection<BiomeNode> nodes, Collection<AdjacencyRule> rules) {
        return new BiomeGraph(nodes, rules).requireMinLegalNeighbors();
    }

    /** Fail loud if any node has fewer than {@link #MIN_LEGAL_NEIGHBORS} legal neighbors - see that constant's own doc for why this is opt-in rather than always-on. Call this on every real/production graph. */
    public BiomeGraph requireMinLegalNeighbors() {
        for (BiomeId id : nodesById.keySet()) {
            long legalNeighbors = nodesById.keySet().stream().filter(other -> !other.equals(id) && isAllowed(id, other)).count();
            if (legalNeighbors < MIN_LEGAL_NEIGHBORS) {
                throw new IllegalArgumentException(
                    "BiomeGraph: " + id + " has only " + legalNeighbors + " legal neighbor(s), fewer than the "
                        + "required minimum of " + MIN_LEGAL_NEIGHBORS + " - add more AdjacencyRules for it."
                );
            }
        }
        return this;
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

    /**
     * Default-deny for a biome next to itself - EXCEPT a biome whose own {@link
     * BiomeNode#selfAdjacencyLimit} raised above 0 (see that field's doc). Pure state-free
     * pairwise predicate - only says self-adjacency is possible, not how many.
     */
    public boolean isAllowed(BiomeId a, BiomeId b) {
        if (a.equals(b)) {
            return node(a).selfAdjacencyLimit() > 0;
        }
        return rulesByPair.containsKey(UnorderedBiomePair.of(a, b));
    }

    public AdjacencyRule rule(BiomeId a, BiomeId b) {
        AdjacencyRule rule = rulesByPair.get(UnorderedBiomePair.of(a, b));
        if (rule == null) {
            throw new IllegalArgumentException("No AdjacencyRule between " + a + " and " + b + " (not allowed)");
        }
        return rule;
    }
}
