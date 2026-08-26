package com.mineutze.worldgen.biomegraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Static config: biome families (nodes) and their adjacency rules (edges).
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
 * moves to {@code SeamGrowthRasterizer}'s own {@code fillGaps} step: a cell
 * that genuinely has no legal biome given its neighbors is a graph
 * misconfiguration (e.g. three mutually-incompatible biomes meeting at a
 * point with nothing compatible with all three) and fails loud there,
 * rather than this class silently guaranteeing one can never happen — see
 * {@link #requireEveryLocalNeighborhoodSolvable} for how that's proven
 * unreachable offline for a well-formed graph.
 */
public final class BiomeGraph {

    private final Map<BiomeId, BiomeNode> nodesById;
    private final Map<UnorderedBiomePair, AdjacencyRule> rulesByPair;

    // Checked at construction time, cheaper than (and well before) the much stronger,
    // actual currently-relied-upon proof in requireEveryLocalNeighborhoodSolvable - see
    // BridgeHopReachability's own doc for what this bound does and doesn't prove. 3 is
    // this graph's own choice of floor, not a universal constant.
    private static final int MAX_BRIDGE_HOPS = 3;

    // A biome with too few legal neighbors gets starved by TriangleIdentitySizeAssignment's
    // own fairness-quota candidate ordering: fairness only reorders candidates already legal
    // for a face, it can't invent legality a narrow biome doesn't have (badlands at degree 1
    // drove the very first triangle-count skew found in this graph's own history). Unlike
    // requireEveryLocalNeighborhoodSolvable, starvation isn't a pure graph-structure property
    // checkable offline - it depends on real mesh placement, not just the rules - so this
    // floor is a cheap, unproven-by-itself heuristic, not a derived guarantee. The real,
    // re-runnable proof that ModBiomeGraph.DEFAULT specifically doesn't starve any of its
    // biomes lives in ModBiomeGraphTest.noBiomeIsStarvedOnARealProductionScaleSolve, which
    // actually runs the CSP against a real mesh instead of reasoning about it in the
    // abstract. 3 is a floor, not a guarantee of fair representation - just the minimum
    // needed for a biome to plausibly show up across a real mesh at all.
    //
    // NOT enforced in the constructor, unlike the bridgeability check above - several
    // algorithm-focused unit tests deliberately build narrow chain/bridge graphs (degree 1-2)
    // to isolate specific CSP behaviors (e.g. a graph with NO simultaneously-legal biome
    // against three fixed neighbors, or a plain chain to test forward-checking propagation).
    // This is a real-graph design constraint, not a universal law of the data structure -
    // real graphs go through the forProduction factory instead, which enforces this.
    private static final int MIN_LEGAL_NEIGHBORS = 3;

    public BiomeGraph(Collection<BiomeNode> nodes, Collection<AdjacencyRule> rules) {
        this.nodesById = indexNodes(nodes);
        this.rulesByPair = indexRules(rules);
        requireAllRuleNodesKnown();
        BridgeHopReachability.requireEveryPairWithinHops(nodesById.keySet().stream().sorted().toList(), this::isAllowed, MAX_BRIDGE_HOPS);
    }

    /**
     * The one entry point a real, shippable biome set should be built through — bundles
     * {@link #requireMinLegalNeighbors()} and {@link #requireEveryLocalNeighborhoodSolvable}
     * so a production graph can't accidentally skip either opt-in check by only remembering
     * one of the two fluent calls. The bare constructor stays public (not narrowed to this
     * package) because roughly half this codebase's own test suite deliberately builds
     * small, narrow-degree graphs — chains, hubs, 3-node triangles — to isolate ONE specific
     * algorithm behavior in total isolation; those fixtures exist to test something else
     * entirely and have no interest in (and are often mathematically incapable of meeting,
     * e.g. a 3-node graph can never reach degree 3) the fairness/coverage concern this
     * factory's checks protect against. See {@code ModBiomeGraph} for this codebase's one
     * actual production call site.
     */
    public static BiomeGraph forProduction(Collection<BiomeNode> nodes, Collection<AdjacencyRule> rules, int maxNeighbors) {
        return new BiomeGraph(nodes, rules).requireMinLegalNeighbors().requireEveryLocalNeighborhoodSolvable(maxNeighbors);
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

    /**
     * Fail loud if any combination of up to {@code maxNeighbors} distinct biomes (bounded
     * by how many neighbors a cell can actually have — Moore/8-way for {@code
     * SeamGrowthRasterizer}'s own growth and gap-fill) has NO biome anywhere in the
     * graph legal against all of them simultaneously — the exact structural dead end that
     * class would otherwise only discover at runtime, mid-generation, and crash on. Proves
     * that dead end unreachable offline instead of merely hoping a well-formed graph makes
     * it rare — see that class's own doc for why failing loud there, rather than silently
     * placing an illegal biome, is the only acceptable runtime behavior if this check is
     * ever skipped or a graph slips through un-validated.
     *
     * <p>Only checks combinations of the FULL size {@code min(maxNeighbors, node count)},
     * not every smaller size too — sound by a simple monotonicity argument: if a set S of
     * that full size has a legal-against-all candidate, that SAME candidate is trivially
     * also legal against every SUBSET of S (fewer simultaneous requirements can only be
     * easier to satisfy), and any smaller real neighborhood can always be padded out to
     * some full-size superset whenever the graph has at least that many biomes — so
     * checking only the maximal size already covers every smaller one for free.
     *
     * <p><b>{@code maxNeighbors} is a caller-chosen bound, not hardcoded to 8 (the real
     * Moore/8-way neighbor count {@code SeamGrowthRasterizer} can face)</b> —
     * some graphs may only be provably solvable at a lower bound, and this method lets a
     * caller find that bound rather than forcing an all-or-nothing check. It does NOT
     * require a universal-filler-style node to prove the full 8-way case, despite that
     * pattern being deliberately removed from this codebase (see this class's own doc) —
     * a graph can satisfy every up-to-8 combination through ORDINARY, narrow adjacency
     * rules alone, as long as no two biomes end up sharing zero common third neighbor.
     * See {@code ModBiomeGraph}'s own doc for a real graph this caught a genuine gap in.
     */
    public BiomeGraph requireEveryLocalNeighborhoodSolvable(int maxNeighbors) {
        List<BiomeId> ids = nodesById.keySet().stream().sorted().toList();
        int comboSize = Math.min(maxNeighbors, ids.size());
        if (comboSize <= 0) {
            return this;
        }
        forEachCombination(ids, comboSize, combo -> {
            boolean solvable = ids.stream().anyMatch(candidate -> combo.stream().allMatch(neighbor -> isAllowed(candidate, neighbor)));
            if (!solvable) {
                throw new IllegalArgumentException(
                    "BiomeGraph: no biome is legal against every member of the neighbor combination " + combo
                        + " simultaneously - a cell surrounded by exactly these biomes would have no legal "
                        + "placement. Add an AdjacencyRule bridging them, or a biome legal against all of them."
                );
            }
        });
        return this;
    }

    private static void forEachCombination(List<BiomeId> ids, int size, Consumer<List<BiomeId>> action) {
        forEachCombination(ids, size, 0, new ArrayList<>(size), action);
    }

    private static void forEachCombination(List<BiomeId> ids, int size, int startIndex, List<BiomeId> current, Consumer<List<BiomeId>> action) {
        if (current.size() == size) {
            action.accept(List.copyOf(current));
            return;
        }
        for (int i = startIndex; i < ids.size(); i++) {
            current.add(ids.get(i));
            forEachCombination(ids, size, i + 1, current, action);
            current.remove(current.size() - 1);
        }
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
