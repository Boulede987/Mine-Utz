package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.regionsolve.topology.mesh.FaceAdjacency;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Groups mutually-adjacent, same-biome free faces into their real connected components -
 * BUT ONLY when at least one member actually NEEDS the help. See {@link
 * FaceWidthPicker#splitWidthForGroup} for why a group matters (one shared felt-region
 * target, split by real need across every member, not each face drawing its own full-range
 * target).
 *
 * <p>Real user finding (2026-09-14): grouping used to form for ANY mutually-adjacent
 * same-biome pair whose biome allowed it, regardless of whether either member actually
 * needed the room - {@link FaceIdentityCsp} decides WHO gets which biome purely from graph
 * legality/fairness, with zero visibility into geometry (identity is decided before width
 * exists at all), so self-adjacency was forming as an accidental byproduct of identity
 * assignment, not a real need. A biome well within reach of its own singleton draw got
 * needlessly split across a group anyway, missing its own target for no reason - "clearly a
 * code problem, not an architecture problem" (real user diagnosis). Fixed by gating
 * formation on {@link AchievableWidthRange}, the SAME real committed-neighbor geometry
 * signal {@link TriangleIdentitySizeAssignment} already computes for CSP domain-pruning
 * (reused, not recomputed): a face NEEDS self-adjacency when its own achievable ceiling
 * can't even reach its biome's declared max alone ("I can't fulfill my max size alone due
 * to the greater size of my neighbours" - real user framing) - mathed out from real
 * geometry, not guessed. A face with no committed neighbor at all (achievable == null,
 * fully unconstrained) never needs it. Once ANY member of a same-biome connected component
 * needs it, the WHOLE component groups (spreads the squeeze across every real cell willing
 * to help, "keep adding couches") - a component where NO member needs it stays ungrouped,
 * every member drawing its own independent full-range singleton width even though they
 * happen to be adjacent and same biome.
 *
 * <p>{@link com.mineutze.worldgen.biomegraph.BiomeNode#selfAdjacencyLimit} still caps
 * connectivity (how many same-biome neighbors one face may have, enforced by {@link
 * FaceIdentityCsp}) - this class only discovers the connected-component SHAPE that cap
 * already produced, then decides whether that shape is worth actually treating as a group.
 */
final class SelfAdjacentPairing {

    private SelfAdjacentPairing() {
    }

    /**
     * @return a symmetric map from face index to every OTHER member of its own self-
     * adjacent group - a face with no real same-biome neighbor this instance, or one whose
     * whole same-biome component has no genuine need for the extra room, simply has no
     * entry.
     */
    static Map<Integer, Set<Integer>> groupedFaces(
        Map<Integer, BiomeId> assigned, FaceAdjacency.Adjacency adjacency, BiomeGraph graph,
        Map<Integer, AchievableWidthRange> achievableRangeByFace
    ) {
        Map<Integer, Set<Integer>> groupPartnersByFace = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        for (int faceIndex : assigned.keySet()) {
            if (visited.contains(faceIndex) || graph.node(assigned.get(faceIndex)).selfAdjacencyLimit() <= 0) {
                continue;
            }
            Set<Integer> component = connectedSameBiomeComponent(faceIndex, assigned, adjacency, visited);
            if (component.size() <= 1) {
                continue; // no real same-biome neighbor this instance - stays a singleton, no entry
            }
            if (component.stream().noneMatch(member -> needsSelfAdjacency(member, assigned.get(member), graph, achievableRangeByFace))) {
                continue; // real same-biome cluster, but nobody in it is actually squeezed - stays independent singletons
            }
            for (int member : component) {
                Set<Integer> others = new LinkedHashSet<>(component);
                others.remove(member);
                groupPartnersByFace.put(member, others);
            }
        }
        return groupPartnersByFace;
    }

    /**
     * Whether {@code member} genuinely can't fulfill its own biome's max size alone, given
     * its real achievable range from committed neighbors - the actual need-gate (see class
     * doc). {@code achievable == null} means no committed neighbor constrains this face at
     * all (fully free), so it never needs help. Deliberately checks against the biome's own
     * MAX, not min: {@link AchievableWidthRange}'s own CSP-domain-pruning already hard-
     * excludes any candidate whose min is unreachable, so "can't even reach min" can never
     * survive to here - the real, live squeeze is "boxed in below my own max."
     */
    private static boolean needsSelfAdjacency(int member, BiomeId biome, BiomeGraph graph, Map<Integer, AchievableWidthRange> achievableRangeByFace) {
        AchievableWidthRange achievable = achievableRangeByFace.get(member);
        if (achievable == null) {
            return false;
        }
        return achievable.maxWidthBlocks() < graph.node(biome).sizeRange().maxWidthBlocks();
    }

    /** BFS over real mesh-adjacency, restricted to faces sharing {@code seed}'s own biome - the real connected-component shape {@link FaceIdentityCsp}'s own per-face self-adjacency cap already produced, discovered here, not re-decided. */
    private static Set<Integer> connectedSameBiomeComponent(
        int seed, Map<Integer, BiomeId> assigned, FaceAdjacency.Adjacency adjacency, Set<Integer> visited
    ) {
        BiomeId biome = assigned.get(seed);
        Set<Integer> component = new LinkedHashSet<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        frontier.add(seed);
        visited.add(seed);
        while (!frontier.isEmpty()) {
            int current = frontier.poll();
            component.add(current);
            for (int neighbor : adjacency.neighborFaceIndices().getOrDefault(current, Set.of())) {
                if (visited.contains(neighbor) || !biome.equals(assigned.get(neighbor))) {
                    continue;
                }
                visited.add(neighbor);
                frontier.add(neighbor);
            }
        }
        return component;
    }
}
