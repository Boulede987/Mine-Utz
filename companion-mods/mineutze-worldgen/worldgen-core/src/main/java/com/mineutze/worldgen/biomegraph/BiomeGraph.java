package com.mineutze.worldgen.biomegraph;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static config: biome families (nodes) and their adjacency rules (edges).
 * See doc/ARCHITECTURE.md layer 1.
 *
 * Validated eagerly at construction (fail loud on a bad config, not lazily
 * during world gen): every rule must reference known nodes, no duplicate
 * rule per pair.
 *
 * No node is ever universally legal — deliberately removed the earlier
 * "universal filler" concept (a designated node required to be allowed next
 * to everything, guaranteeing backtracking always has a legal fallback).
 * That guarantee was purchased by forcing one biome (plains) to be
 * permanently unrestrictable — every other biome could get real adjacency
 * rules, plains couldn't. Every biome, including plains, can now be
 * restricted like any other. The termination guarantee it used to provide
 * moves to {@link com.mineutze.worldgen.regionsolve.GapFiller}: a cell that
 * genuinely has no legal biome given its neighbors is a graph
 * misconfiguration (e.g. three mutually-incompatible biomes meeting at a
 * point with nothing compatible with all three) and fails loud there,
 * rather than this class silently guaranteeing one can never happen.
 */
public final class BiomeGraph {

    private final Map<BiomeId, BiomeNode> nodesById;
    private final Map<UnorderedBiomePair, AdjacencyRule> rulesByPair;

    // A cell touching two biomes with no direct rule between them can only be resolved
    // by GapFiller.makeLegalHere reassigning a chain of neighbors, capped at
    // GapFiller.MAX_REASSIGN_DEPTH (2) - which, worked through by hand, can bridge two
    // biomes at most MAX_BRIDGE_HOPS edges apart in this allow-graph (A-X-Y-B: edge
    // A-X to start the chain, edge X-Y to extend it one hop further, edge Y-B for the
    // final link to actually become legal). Duplicated here rather than referenced
    // directly - this module has no dependency on regionsolve (see class doc) - so
    // keep this in sync by hand if MAX_REASSIGN_DEPTH ever changes.
    private static final int MAX_BRIDGE_HOPS = 3;

    public BiomeGraph(Collection<BiomeNode> nodes, Collection<AdjacencyRule> rules) {
        this.nodesById = indexNodes(nodes);
        this.rulesByPair = indexRules(rules);
        requireAllRuleNodesKnown();
        requireEveryPairBridgeableWithinReassignBudget();
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

    /**
     * Global connectivity (every node reachable from every other) is necessary but not
     * sufficient - two biomes can be technically connected through a long detour and
     * still be impossible for {@code GapFiller} to actually bridge at a real point of
     * contact, because its neighbor-reassignment search is bounded (see {@link
     * #MAX_BRIDGE_HOPS}'s doc). Catches that at graph-construction time instead of a
     * runtime crash mid-world-gen: for every pair of biomes with no direct rule,
     * requires a path of at most {@link #MAX_BRIDGE_HOPS} edges through other allowed
     * pairs.
     *
     * This is necessary, not sufficient, for {@code GapFiller} to always resolve a
     * dead end - it only rules out the "impossible in principle" case (the graph
     * itself is too sparse). It says nothing about whether enough LOCAL donor
     * capacity (nearby patches large enough to spare a cell without dropping below
     * their own minimum size) exists at any specific point two such biomes actually
     * meet - that depends on runtime placement and patch sizes, not the graph alone,
     * and is exactly what {@code GapFiller}'s own patch-drop retry exists to absorb
     * when it happens (see doc/KNOWN_ISSUES.md).
     */
    private void requireEveryPairBridgeableWithinReassignBudget() {
        List<BiomeId> ids = nodesById.keySet().stream().sorted().toList();
        for (BiomeId start : ids) {
            Map<BiomeId, Integer> hopsFromStart = shortestHopsFrom(start);
            for (BiomeId other : ids) {
                if (start.compareTo(other) >= 0 || isAllowed(start, other)) {
                    continue; // unordered pair already checked from the other side, or trivially fine
                }
                Integer hops = hopsFromStart.get(other);
                if (hops == null || hops > MAX_BRIDGE_HOPS) {
                    throw new IllegalArgumentException(
                        "BiomeGraph: " + start + " and " + other + " have no direct AdjacencyRule and "
                            + (hops == null ? "no bridging path at all through the rest of the graph"
                                : "need " + hops + " hops to bridge, more than GapFiller's neighbor-reassignment "
                                    + "can search (" + MAX_BRIDGE_HOPS + ")")
                            + " - add a shorter path between them (a direct rule, or a rule to some third biome "
                            + "that's within " + MAX_BRIDGE_HOPS + " hops of both)."
                    );
                }
            }
        }
    }

    private Map<BiomeId, Integer> shortestHopsFrom(BiomeId start) {
        Map<BiomeId, Integer> hops = new HashMap<>();
        hops.put(start, 0);
        Deque<BiomeId> frontier = new ArrayDeque<>(List.of(start));
        while (!frontier.isEmpty()) {
            BiomeId current = frontier.poll();
            int nextHops = hops.get(current) + 1;
            for (BiomeId candidate : nodesById.keySet()) {
                if (!candidate.equals(current) && isAllowed(current, candidate) && !hops.containsKey(candidate)) {
                    hops.put(candidate, nextHops);
                    frontier.add(candidate);
                }
            }
        }
        return hops;
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
}
