package com.mineutze.worldgen.biomegraph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Static config: biome families (nodes) + adjacency rules (edges).
 *
 * Validated eagerly at construction (fail loud on bad config, not lazily during
 * world gen): every rule must reference known nodes, no duplicate rule per pair.
 *
 * No node ever universally legal. Cell with no legal biome given neighbors = graph
 * misconfiguration (e.g. 3 mutually-incompatible biomes meeting at a point) - fails
 * loud via whichever CSP is live ({@link
 * com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment}
 * today). See {@link #requireEveryLocalNeighborhoodSolvable} (proves this unreachable
 * offline for well-formed graph). See DECISIONS.md 2026-08-07.
 */
public final class BiomeGraph {

    private final Map<BiomeId, BiomeNode> nodesById;
    private final Map<UnorderedBiomePair, AdjacencyRule> rulesByPair;

    // Biome with too few legal neighbors gets starved by TriangleIdentitySizeAssignment's
    // fairness-quota ordering: fairness only reorders already-legal candidates, can't
    // invent legality. Starvation depends on real mesh placement, not just rules - so this
    // floor is a cheap heuristic, not a derived guarantee (real proof for
    // ModBiomeGraph.DEFAULT: ModBiomeGraphTest.noBiomeIsStarvedOnARealProductionScaleSolve).
    // NOT enforced in constructor - some unit tests deliberately build narrow chain/bridge
    // graphs (degree 1-2) to isolate CSP behaviors; real graphs go through forProduction,
    // which enforces this. See DECISIONS.md (undated, pre-2026-08).
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
     *
     * <p><b>Does NOT call {@link #requireEveryLocalNeighborhoodSolvable} anymore</b> - real
     * but deliberately non-blocking at real catalog scale, see DECISIONS.md 2026-08-23.
     * Real production validation runs actual generation instead - see {@code
     * ModBiomeGraphTest}'s real-solve tests for both {@code DEFAULT} and {@code OVERWORLD}.
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
     * Fail loud if any node's every legal neighbor shares its exact {@link BiomeSizeRange} -
     * static, mesh-independent structural gap. Unlike {@link
     * #requireEveryLocalNeighborhoodSolvable}, not prone to overcounting: only needs each
     * node's static legal-neighbor list + each neighbor's size range, no mesh/instance
     * narrowing - property of graph alone, checkable once, always sound.
     *
     * <p>NOT part of {@link #forProduction}'s bundle: real catalog fails this TODAY, wiring
     * it in would break {@code ModBiomeGraph.OVERWORLD}'s static initializer before those
     * gaps get closed with a real {@link AdjacencyRule} - a game-design call (which
     * differently-sized biome each should border), not this method's job. Call explicitly
     * once that fix lands. See DECISIONS.md (undated) "Cross-tier-neighbor check".
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
     * Fail loud if any REALIZABLE neighbor ring - {@code maxNeighbors} biomes in a cycle
     * where every CONSECUTIVE pair (wrapping) is legal, exactly the shape a real hex mesh
     * face's neighbors take - has no biome anywhere legal against every ring member
     * simultaneously. "Realizable" matters: with exactly 3 faces meeting at every vertex
     * (see {@code HexMeshBuilder}'s doc), consecutive neighbors of a face always share an
     * edge with each other too - a face's neighbor set is never an arbitrary pick of {@code
     * maxNeighbors} biomes, it's a ring where adjacent members are mutually legal.
     *
     * <p>Real face always has exactly {@code maxNeighbors} neighbors once non-hull-touching,
     * never fewer - checks the one fully-closed shape directly (PARTIAL neighborhood, some
     * free/undecided, is {@link #requireEveryPartialNeighborhoodSolvable}'s concern).
     *
     * <p>{@code maxNeighbors} is caller-chosen (capped at graph's own node count when
     * smaller) - real hex mesh's ≤6 face degree for {@link ModBiomeGraph#OVERWORLD}, not
     * hardcoded. See DECISIONS.md 2026-08-23.
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
     * Fail loud if any unordered subset of {@code 2..maxNeighbors-1} DISTINCT biomes has no
     * universal legal candidate.
     *
     * <p><b>Does NOT require subset members mutually legal to each other</b> - real
     * distinction from {@link #requireEveryLocalNeighborhoodSolvable}'s ring restriction,
     * why this is a separate method. Real hex face neighbors sharing a mesh vertex are only
     * ever CONSECUTIVE ones (hex vertex-degree-3, see {@code HexMeshBuilder}'s doc) -
     * non-consecutive neighbors share no vertex, need no direct legality. Subset smaller
     * than max neighbor count = some neighbors already decided (any combination - real
     * incremental construction fixes them in whatever order CSP's candidate ordering
     * reaches them, not ring position), rest still open.
     *
     * <p><b>Stops at {@code maxNeighbors - 1} on purpose</b> - subset of exactly {@code
     * maxNeighbors} = face's entire closed neighbor ring, where every consecutive pair must
     * already be mutually legal (real geometric constraint) - {@link
     * #requireEveryLocalNeighborhoodSolvable} covers that full-ring case; this covers the
     * partial case.
     *
     * <p>NOT part of {@link #forProduction}'s bundle, like its ring-based sibling, never
     * will be at this scale - explicit non-blocking diagnostic for a SPECIFIC real crash,
     * not a blanket completeness gate. See DECISIONS.md 2026-09-11 "Partial-neighborhood
     * completeness check" (full incident, measured numbers, alternatives considered).
     */
    public BiomeGraph requireEveryPartialNeighborhoodSolvable(int maxNeighbors) {
        List<List<BiomeId>> unsolvable = findUnsolvablePartialNeighborhoods(maxNeighbors);
        if (!unsolvable.isEmpty()) {
            throw new IllegalArgumentException(
                "BiomeGraph: no biome is legal against every member of the partial neighbor subset " + unsolvable.get(0)
                    + " simultaneously - a face with exactly these already-decided neighbors (the rest still free) "
                    + "would have no legal placement. Add an AdjacencyRule bridging them, or a biome legal against "
                    + "all of them. (" + unsolvable.size() + " total unsolvable subset(s) found.)"
            );
        }
        return this;
    }

    /** Report-only counterpart to {@link #requireEveryPartialNeighborhoodSolvable} - every unsolvable subset, not just the first, for measuring real scope before deciding blocking vs. tracked-diagnostic (same convention {@link #requireEveryLocalNeighborhoodSolvable}'s own real 47,623/201,552 measurement already established). */
    public List<List<BiomeId>> findUnsolvablePartialNeighborhoods(int maxNeighbors) {
        List<BiomeId> ids = nodesById.keySet().stream().sorted().toList();
        int maxSubsetSize = Math.min(maxNeighbors - 1, ids.size());
        List<List<BiomeId>> unsolvable = new ArrayList<>();
        for (int subsetSize = 2; subsetSize <= maxSubsetSize; subsetSize++) {
            forEachCombination(ids, subsetSize, subset -> {
                boolean solvable = ids.stream().anyMatch(candidate -> subset.stream().allMatch(member -> isAllowed(candidate, member)));
                if (!solvable) {
                    unsolvable.add(subset);
                }
            });
        }
        return unsolvable;
    }

    /**
     * ACTIONABLE version of {@link #findUnsolvablePartialNeighborhoods} - reports only
     * MINIMAL unsolvable subsets (no proper subset also unsolvable), not every superset
     * consequence: adding more already-fixed neighbors to an unsolvable subset only ever
     * keeps it unsolvable (monotonic property - more constraints never fix a missing
     * universal candidate), so non-minimal result is noise implied by a smaller minimal one.
     *
     * <p>Also a real speedup: subset containing an already-found minimal conflict is
     * skipped entirely (guaranteed unsolvable, testing again wasted work) - larger subsets
     * get cheaper as more minimal conflicts accumulate. See DECISIONS.md 2026-09-11
     * (measured reduction).
     */
    public List<List<BiomeId>> findMinimalUnsolvablePartialNeighborhoods(int maxNeighbors) {
        List<BiomeId> ids = nodesById.keySet().stream().sorted().toList();
        int maxSubsetSize = Math.min(maxNeighbors - 1, ids.size());
        List<List<BiomeId>> minimal = new ArrayList<>();
        for (int subsetSize = 2; subsetSize <= maxSubsetSize; subsetSize++) {
            forEachCombination(ids, subsetSize, subset -> {
                if (minimal.stream().anyMatch(subset::containsAll)) {
                    return; // superset of an already-known minimal conflict - unsolvable, but not minimal, skip re-testing
                }
                if (!isPlaceableAmongNeighbors(subset, maxNeighbors)) {
                    return; // couldn't physically occur as simultaneous neighbors at all - moot, not a real conflict
                }
                boolean solvable = ids.stream().anyMatch(candidate -> subset.stream().allMatch(member -> isAllowed(candidate, member)));
                if (!solvable) {
                    minimal.add(subset);
                }
            });
        }
        return minimal;
    }

    /**
     * Whether {@code subset} could ever physically occur as some of a real face's
     * already-decided neighbors - independent of whether a legal CENTER candidate exists
     * ({@link #isAllowed}'s own concern). Real face has exactly {@code maxNeighbors} ring
     * slots; {@code maxNeighbors - subset.size()} can be left undecided ("free"), used as
     * separators. Two subset members in CONSECUTIVE ring positions share a real mesh
     * vertex, need mutual legality ({@link #isAllowed}-checked between each other, not just
     * against a future center) - only if no free slot separates them. Found the hard way
     * (2026-09-11): for {@code subset.size() <= 3}, always placeable (each member gets own
     * separating free slot) - matches {@link #requireEveryPartialNeighborhoodSolvable} not
     * needing member-legality for small subsets. For 4 or 5, pigeonhole forces some members
     * adjacent, and those pairs must be mutually legal - failing this isn't a design gap,
     * it's a combination that could never exist as neighbors regardless of center biome.
     *
     * <p>Reduces to: can {@code subset} split into at most {@code maxNeighbors -
     * subset.size()} ordered runs (paths) where every consecutive pair within a run is
     * mutually legal - minimum PATH COVER under the mutual-legality relation.
     * Brute-forced over every permutation (subset size always {@code <= maxNeighbors - 1
     * <= 5} in practice, at most 120 orderings - trivial).
     */
    public boolean isPlaceableAmongNeighbors(List<BiomeId> subset, int maxNeighbors) {
        int freeSlots = maxNeighbors - subset.size();
        if (subset.size() <= freeSlots) {
            return true; // every member can get its own separating free slot - never needs any mutual legality
        }
        return minPathCoverUnknownGraph(subset) <= freeSlots;
    }

    private int minPathCoverUnknownGraph(List<BiomeId> subset) {
        return minIllegalAdjacentPairsOverAllOrderings(subset) + 1;
    }

    /** Fewest mutually-illegal CONSECUTIVE pairs across every ordering of {@code subset} - minimum path cover (see {@link #isPlaceableAmongNeighbors}) is this plus one. */
    private int minIllegalAdjacentPairsOverAllOrderings(List<BiomeId> subset) {
        return minIllegalAdjacentPairsFrom(new ArrayList<>(subset), new ArrayList<>(), Integer.MAX_VALUE);
    }

    private int minIllegalAdjacentPairsFrom(List<BiomeId> remaining, List<BiomeId> orderedSoFar, int bestSoFar) {
        if (remaining.isEmpty()) {
            return countIllegalAdjacentPairs(orderedSoFar);
        }
        int best = bestSoFar;
        for (int i = 0; i < remaining.size(); i++) {
            BiomeId next = remaining.get(i);
            List<BiomeId> nextRemaining = new ArrayList<>(remaining);
            nextRemaining.remove(i);
            orderedSoFar.add(next);
            int result = minIllegalAdjacentPairsFrom(nextRemaining, orderedSoFar, best);
            orderedSoFar.remove(orderedSoFar.size() - 1);
            best = Math.min(best, result);
        }
        return best;
    }

    private int countIllegalAdjacentPairs(List<BiomeId> ordered) {
        int count = 0;
        for (int i = 0; i + 1 < ordered.size(); i++) {
            if (!isAllowed(ordered.get(i), ordered.get(i + 1))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Stable fingerprint of everything {@link #findMinimalUnsolvablePartialNeighborhoods}
     * actually depends on (node id set + legal-pair relation) - lets caller cache that
     * result to disk, skip recomputing when graph hasn't changed. Does NOT include {@code
     * BiomeSizeRange}/{@code selfAdjacencyLimit}/blend width - none affect adjacency
     * LEGALITY, the only thing this completeness question depends on.
     */
    public String rulesFingerprint() {
        List<String> nodeIds = nodesById.keySet().stream().map(BiomeId::toString).sorted().toList();
        List<String> pairs = rulesByPair.keySet().stream()
            .map(pair -> pair.first() + "|" + pair.second())
            .sorted()
            .toList();
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(String.join(",", nodeIds).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.join(",", pairs).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable - should never happen on a real JVM", e);
        }
    }

    /** Enumerates every unordered {@code size}-element subset of {@code ids} exactly once, in sorted order - no legality/adjacency restriction on membership, unlike {@link #forEachLegalRing}. */
    private static void forEachCombination(List<BiomeId> ids, int size, Consumer<List<BiomeId>> action) {
        forEachCombinationFrom(ids, size, 0, new ArrayList<>(), action);
    }

    private static void forEachCombinationFrom(List<BiomeId> ids, int size, int startIndex, List<BiomeId> current, Consumer<List<BiomeId>> action) {
        if (current.size() == size) {
            action.accept(List.copyOf(current));
            return;
        }
        for (int i = startIndex; i < ids.size(); i++) {
            current.add(ids.get(i));
            forEachCombinationFrom(ids, size, i + 1, current, action);
            current.remove(current.size() - 1);
        }
    }

    /**
     * Enumerates every simple cycle of exactly {@code ringLength} distinct biomes where
     * each consecutive pair (wrapping) is legal - each cycle reported once, not once per
     * rotation/reflection: fixes cycle's lowest-by-{@link BiomeId#compareTo} biome as
     * start, requires second biome visited to sort before last (standard canonical-form
     * trick). Precomputes each biome's legal-neighbor list once so search only branches
     * into real candidates, not whole graph each step.
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
     * Default-deny for a biome next to itself - EXCEPT a biome whose own {@link
     * BiomeNode#selfAdjacencyLimit} raised above 0 (see that field's doc: lets biome span
     * multiple hexes as one felt region, no old growth/merge pipeline mechanism - each hex
     * still gets own independent identity+size CSP assignment + own independent {@link
     * com.mineutze.worldgen.regionsolve.topology.placement.WeightedVertexPlacement}
     * geometry). Only makes self-adjacency TOPOLOGICALLY possible - actual COUNT is a
     * separate stateful check in {@link
     * com.mineutze.worldgen.regionsolve.topology.identity.TriangleIdentitySizeAssignment}
     * (this method is pure state-free pairwise predicate; cardinality depends on how many
     * neighbors already assigned, not just whether pair allowed).
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
