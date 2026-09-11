package com.mineutze.worldgen.biomegraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
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
    }

    /**
     * The one entry point a real, shippable biome set should be built through — applies
     * {@link #requireMinLegalNeighbors()} so a production graph can't accidentally skip
     * it. The bare constructor stays public (not narrowed to this package) because
     * roughly half this codebase's own test suite deliberately builds small, narrow-
     * degree graphs — chains, hubs, 3-node triangles — to isolate ONE specific algorithm
     * behavior in total isolation; those fixtures exist to test something else entirely
     * and have no interest in (and are often mathematically incapable of meeting, e.g. a
     * 3-node graph can never reach degree 3) the fairness/coverage concern this factory's
     * check protects against.
     *
     * <p><b>Does NOT call {@link #requireEveryLocalNeighborhoodSolvable} anymore.</b> That
     * check tests every REALIZABLE neighbor ring (a real improvement over an earlier,
     * even cruder version that tested unrealizable arrangements too — see that method's
     * own doc) — but at real biome-catalog scale it's still checking rings far beyond
     * what the CSP's own incremental, legality-threaded construction could ever actually
     * reach: measured directly on the real 48-biome {@code ModBiomeGraph.OVERWORLD} data,
     * 201,552 realizable 6-rings exist and 47,623 of them (≈24%) have no universal
     * candidate — not "one gap to patch," a structural certainty for any thematically
     * narrow graph at this scale, since no realistic graph has a biome legal against an
     * arbitrary ring of 6 unrelated others. Demanding that as a hard gate would make
     * every well-designed, real biome catalog un-buildable. The method itself stays
     * available (still sound, still a real measurement) for explicit, non-blocking
     * diagnostic use — real production validation instead runs actual generation
     * (repeatedly re-solving real, reachable local neighborhoods against real fixed
     * surrounding context, many seeds) — see {@code ModBiomeGraphTest}'s own real-solve
     * tests for both {@code DEFAULT} and {@code OVERWORLD}.
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

    /**
     * Fail loud if any node's every legal neighbor shares its own exact {@link
     * BiomeSizeRange} - a real, static, mesh-independent structural gap, not an
     * instance-specific one: found directly against the real 48-biome catalog, both
     * {@code DEEP_LUKEWARM_OCEAN} and {@code MANGROVE_SWAMP}'s own legal neighbors are
     * ALL the same size range as themselves.
     *
     * <p>This check's own rationale predates a redesign of how size actually gets
     * realized (per-edge additive bias, {@code FaceSizeFeasibility}, replaced by {@link
     * com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement}'s power-diagram
     * vertex placement) - under the OLD mechanism, two same-tier neighbors produced
     * EXACTLY zero correction, a provable dead end. The new mechanism has no such hard
     * zero (it differentiates by weight MAGNITUDE, not by "which direction do we each
     * want," so two same-tier neighbors with different chosen widths still get a real,
     * if imperfect, correction) - but measured directly post-redesign, same-tier
     * neighborhoods still correlate with measurably worse size fidelity:
     * {@code DEEP_LUKEWARM_OCEAN} averaged ~35% undersized across a real 10-seed sample,
     * a real, if softer, consequence of the same root issue, not eliminated by the
     * redesign. This check's underlying signal remains a real, useful floor, even though
     * its exact "can never reach target, in any mesh" framing is now an overstatement.
     *
     * <p>Unlike {@link #requireEveryLocalNeighborhoodSolvable}, this is NOT prone to
     * overcounting: it only needs each node's own static legal-neighbor list and each
     * neighbor's own size range, no mesh/instance-dependent domain narrowing involved at
     * all - a property of the graph alone, checkable once, always sound.
     *
     * <p>Deliberately NOT part of {@link #forProduction}'s automatic bundle: the real
     * catalog fails this TODAY (see above), so wiring it in would break {@code
     * ModBiomeGraph.OVERWORLD}'s own static initializer before those two real gaps are
     * actually closed with a real {@link AdjacencyRule} - a game-design call (which
     * differently-sized biome each should legally border), not this method's job to make.
     * Call this explicitly once that fix lands.
     */
    public BiomeGraph requireEveryBiomeHasACrossTierNeighbor() {
        for (BiomeId id : nodesById.keySet()) {
            BiomeSizeRange ownSizeRange = node(id).sizeRange();
            List<BiomeId> legalNeighbors = nodesById.keySet().stream()
                .filter(other -> !other.equals(id) && isAllowed(id, other))
                .sorted()
                .toList();
            boolean hasCrossTierNeighbor = legalNeighbors.stream().anyMatch(other -> !node(other).sizeRange().equals(ownSizeRange));
            if (!hasCrossTierNeighbor) {
                throw new IllegalArgumentException(
                    "BiomeGraph: " + id + " (size range " + ownSizeRange + ") has no legal neighbor in a "
                        + "DIFFERENT size range - every legal neighbor is the same tier: " + legalNeighbors + ". "
                        + "Add an AdjacencyRule to some biome with a different BiomeSizeRange."
                );
            }
        }
        return this;
    }

    /**
     * Fail loud if any REALIZABLE neighbor ring — {@code maxNeighbors} biomes arranged
     * in a cycle where every CONSECUTIVE pair (wrapping around) is itself a legal pair,
     * exactly the shape a real hex mesh face's neighbors take — has no biome anywhere in
     * the graph legal against every member of that ring simultaneously. "Realizable"
     * matters: with exactly 3 faces meeting at every vertex (see {@code HexMeshBuilder}'s
     * own doc), two CONSECUTIVE neighbors of a face always share an edge with EACH OTHER
     * too, not just with the face between them — a face's real neighbor set is never an
     * arbitrary unordered pick of {@code maxNeighbors} biomes, it's a ring where adjacent
     * members are mutually legal.
     *
     * <p>Replaces an earlier version that tested every unordered SUBSET of {@code
     * maxNeighbors} biomes, including arrangements no real mesh could ever produce — a
     * real false positive on a 48-biome graph traced this directly: the first subset it
     * tried alphabetically included two biomes with no rule between them at all, an
     * arrangement no hex face could ever actually have as its own ring, yet the old check
     * demanded some biome be legal against it anyway. Checking only legal RINGS fixes
     * that. It also removes the old version's monotonicity argument entirely — a real
     * face always has exactly {@code maxNeighbors} neighbors once it's not hull-touching,
     * never fewer, so there's no smaller-neighborhood case to reason about covering "for
     * free"; this checks the one real shape directly.
     *
     * <p>{@code maxNeighbors} is a caller-chosen bound (capped at the graph's own node
     * count when smaller) — the real hex mesh's own ≤6 face degree for {@link
     * ModBiomeGraph#OVERWORLD}, not hardcoded.
     */
    public BiomeGraph requireEveryLocalNeighborhoodSolvable(int maxNeighbors) {
        List<BiomeId> ids = nodesById.keySet().stream().sorted().toList();
        int ringLength = Math.min(maxNeighbors, ids.size());
        if (ringLength < 3) {
            return this; // no cycle of fewer than 3 distinct biomes is a real shape to check
        }
        forEachLegalRing(ids, ringLength, this::isAllowed, ring -> {
            boolean solvable = ids.stream().anyMatch(candidate -> ring.stream().allMatch(member -> isAllowed(candidate, member)));
            if (!solvable) {
                throw new IllegalArgumentException(
                    "BiomeGraph: no biome is legal against every member of the realizable neighbor ring " + ring
                        + " simultaneously - a face surrounded by exactly these biomes, in this ring order, would "
                        + "have no legal placement. Add an AdjacencyRule bridging them, or a biome legal against all of them."
                );
            }
        });
        return this;
    }

    /**
     * Enumerates every simple cycle of exactly {@code ringLength} distinct biomes where
     * each consecutive pair (wrapping around) is a legal pair — each cycle reported
     * exactly once, not once per rotation/reflection: fixes the cycle's own
     * lowest-by-{@link BiomeId#compareTo} biome as the start and requires the second
     * biome visited to sort before the last one, a standard canonical-form trick for
     * cycle enumeration. Precomputes each biome's own legal-neighbor list once so the
     * search only ever branches into real candidates, not the whole graph at every step.
     */
    private static void forEachLegalRing(
        List<BiomeId> ids, int ringLength, BiPredicate<BiomeId, BiomeId> isAllowed, Consumer<List<BiomeId>> action
    ) {
        Map<BiomeId, List<BiomeId>> legalNeighborsById = new HashMap<>();
        for (BiomeId id : ids) {
            List<BiomeId> neighbors = new ArrayList<>();
            for (BiomeId other : ids) {
                if (!other.equals(id) && isAllowed.test(id, other)) {
                    neighbors.add(other);
                }
            }
            legalNeighborsById.put(id, neighbors);
        }

        for (BiomeId start : ids) {
            List<BiomeId> path = new ArrayList<>(ringLength);
            path.add(start);
            extendLegalRing(start, path, ringLength, legalNeighborsById, isAllowed, action);
        }
    }

    private static void extendLegalRing(
        BiomeId start, List<BiomeId> path, int ringLength,
        Map<BiomeId, List<BiomeId>> legalNeighborsById, BiPredicate<BiomeId, BiomeId> isAllowed, Consumer<List<BiomeId>> action
    ) {
        if (path.size() == ringLength) {
            BiomeId last = path.get(path.size() - 1);
            if (isAllowed.test(last, start) && path.get(1).compareTo(last) < 0) {
                action.accept(List.copyOf(path));
            }
            return;
        }
        BiomeId current = path.get(path.size() - 1);
        for (BiomeId candidate : legalNeighborsById.get(current)) {
            if (candidate.compareTo(start) <= 0 || path.contains(candidate)) {
                continue; // must stay above the ring's own fixed minimum, and stay a simple (repeat-free) cycle
            }
            path.add(candidate);
            extendLegalRing(start, path, ringLength, legalNeighborsById, isAllowed, action);
            path.remove(path.size() - 1);
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

    /**
     * Default-deny for a biome next to itself, same as always - EXCEPT a biome whose own
     * {@link BiomeNode#selfAdjacencyLimit} is raised above 0 (see that field's own doc:
     * lets a biome span multiple ordinary hexes as one felt region, without any of the
     * old growth/merge pipeline's mechanism coming back - each hex still gets its own
     * fully independent identity+size CSP assignment and its own independent {@link
     * com.mineutze.worldgen.regionsolve.topology.WeightedVertexPlacement} geometry).
     * This only makes self-adjacency TOPOLOGICALLY possible - the actual COUNT is a
     * separate, stateful check in {@link
     * com.mineutze.worldgen.regionsolve.topology.TriangleIdentitySizeAssignment} (this
     * method is a pure, state-free pairwise predicate, and cardinality inherently depends
     * on how many neighbors are ALREADY assigned, not just whether the pair is allowed at
     * all).
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
