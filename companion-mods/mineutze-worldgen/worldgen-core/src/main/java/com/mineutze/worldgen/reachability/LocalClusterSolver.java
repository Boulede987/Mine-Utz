package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.RawRuleTable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Joint CSP over a free-face cluster - real, complete backtracking (plain, no forward-
 * checking/domain-pruning sophistication: correctness first, a real stated limitation, see
 * this class's own doc for when that could matter). EXISTENTIAL: finds SOME legal joint
 * assignment if one exists, same discipline as {@code coherence} package's own searches -
 * not tied to any worldSeed's specific RNG-driven candidate choice.
 *
 * <p><b>Only checks pairwise adjacency legality</b> ({@link RawRuleTable#isLegal}) between
 * every hex-adjacent pair (free-free and free-fixed) - does NOT check {@link
 * RawRuleTable#requiredNeighborsOf} or size/area constraints (Motif 1/2/3 territory,
 * deliberately out of scope, see package doc). A cluster this solver calls SAT could still
 * be geometrically infeasible for reasons this solver never sees - a real, stated boundary,
 * not silently ignored.
 */
public final class LocalClusterSolver {

    private LocalClusterSolver() {
    }

    public static Optional<Map<AxialPos, BiomeId>> solve(RawRuleTable table, Set<AxialPos> freeFaces, Map<AxialPos, BiomeId> fixedContext) {
        return solve(table, freeFaces, fixedContext, Map.of());
    }

    /**
     * @param forcedAssignment pins specific free faces to a specific target biome before
     *                         search starts - real, fully legality-checked, never bypasses a
     *                         constraint. Used by reachability searches to steer toward a
     *                         desired outcome instead of accepting whatever legal value
     *                         plain backtracking finds first.
     */
    public static Optional<Map<AxialPos, BiomeId>> solve(
        RawRuleTable table, Set<AxialPos> freeFaces, Map<AxialPos, BiomeId> fixedContext, Map<AxialPos, BiomeId> forcedAssignment
    ) {
        List<AxialPos> order = bfsOrder(freeFaces);
        Map<AxialPos, BiomeId> assignment = new LinkedHashMap<>();
        boolean solved = backtrack(table, order, 0, fixedContext, forcedAssignment, assignment);
        return solved ? Optional.of(Map.copyOf(assignment)) : Optional.empty();
    }

    /**
     * BFS order from an arbitrary seed within {@code freeFaces} - assigns adjacent faces
     * close together in search order, propagating real constraints earlier than an
     * arbitrary iteration order would. Package-private (not private): {@link
     * SizeAwareLocalClusterSolver} reuses this same, purely structural ordering - no
     * RawRuleTable/legality dependency here to duplicate.
     */
    static List<AxialPos> bfsOrder(Set<AxialPos> freeFaces) {
        List<AxialPos> order = new ArrayList<>(freeFaces.size());
        Set<AxialPos> visited = new HashSet<>();
        Deque<AxialPos> frontier = new ArrayDeque<>();
        for (AxialPos start : freeFaces) {
            if (!visited.add(start)) {
                continue;
            }
            frontier.add(start);
            while (!frontier.isEmpty()) {
                AxialPos face = frontier.poll();
                order.add(face);
                for (AxialPos neighbor : face.neighbors()) {
                    if (freeFaces.contains(neighbor) && visited.add(neighbor)) {
                        frontier.add(neighbor);
                    }
                }
            }
        }
        return order;
    }

    private static boolean backtrack(
        RawRuleTable table, List<AxialPos> order, int index, Map<AxialPos, BiomeId> fixedContext,
        Map<AxialPos, BiomeId> forcedAssignment, Map<AxialPos, BiomeId> assignment
    ) {
        if (index == order.size()) {
            return true;
        }
        AxialPos face = order.get(index);
        BiomeId forced = forcedAssignment.get(face);
        for (BiomeId candidate : table.allBiomeIds()) {
            if (forced != null && !candidate.equals(forced)) {
                continue;
            }
            if (!consistent(table, fixedContext, assignment, face, candidate)) {
                continue;
            }
            assignment.put(face, candidate);
            if (backtrack(table, order, index + 1, fixedContext, forcedAssignment, assignment)) {
                return true;
            }
            assignment.remove(face);
        }
        return false;
    }

    private static boolean consistent(RawRuleTable table, Map<AxialPos, BiomeId> fixedContext, Map<AxialPos, BiomeId> assignment, AxialPos face, BiomeId candidate) {
        for (AxialPos neighbor : face.neighbors()) {
            BiomeId neighborValue = assignment.get(neighbor);
            if (neighborValue == null) {
                neighborValue = fixedContext.get(neighbor);
            }
            if (neighborValue != null && !table.isLegal(candidate, neighborValue)) {
                return false;
            }
        }
        return true;
    }
}
