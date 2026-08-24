package com.mineutze.worldgen.biomegraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Cheap, necessary-but-not-sufficient sanity bound on an adjacency graph, meant to run
 * well before a much more expensive combinatorial check (see {@link
 * BiomeGraph#requireEveryLocalNeighborhoodSolvable}): two ids with no direct allowed
 * pair between them must at least be connected through SOME chain of allowed pairs
 * within {@code maxHops} hops (A-X-Y-B: pair A-X, pair X-Y, pair Y-B). A graph failing
 * this is too sparse to work no matter what runtime fallback exists.
 *
 * <p>Necessary, not sufficient, for a graph to actually work at runtime — this only
 * rules out the "impossible in principle" case (the graph itself is too sparse). It
 * says nothing about whether enough LOCAL donor capacity (nearby patches large enough
 * to spare a cell without dropping below their own minimum size) exists at any specific
 * point two such biomes actually meet — that depends on runtime placement and patch
 * sizes, not the graph alone, and is exactly the kind of runtime-only failure a
 * geometry-stage fallback still needs to absorb when it happens.
 *
 * <p>{@code maxHops} is a caller-chosen bound, not tied to any specific runtime
 * mechanism's own search depth — no depth-bounded reassignment mechanism exists in this
 * codebase currently (see {@link BiomeGraph#requireEveryLocalNeighborhoodSolvable}'s own
 * doc for what actually backs a graph's real solvability up today); it's just a
 * reasonable, cheap-to-check floor a caller picks for its own graph.
 */
final class BridgeHopReachability {

    private BridgeHopReachability() {
    }

    /**
     * Fail loud if any pair of {@code ids} with no direct {@code isAllowed} rule needs
     * more than {@code maxHops} hops to bridge through some chain of other allowed
     * pairs — see this class's own doc for what that does and doesn't prove.
     */
    static void requireEveryPairWithinHops(List<BiomeId> ids, BiPredicate<BiomeId, BiomeId> isAllowed, int maxHops) {
        for (BiomeId start : ids) {
            Map<BiomeId, Integer> hopsFromStart = shortestHopsFrom(start, ids, isAllowed);
            for (BiomeId other : ids) {
                if (start.compareTo(other) >= 0 || isAllowed.test(start, other)) {
                    continue; // unordered pair already checked from the other side, or trivially fine
                }
                Integer hops = hopsFromStart.get(other);
                if (hops == null || hops > maxHops) {
                    throw new IllegalArgumentException(
                        "BiomeGraph: " + start + " and " + other + " have no direct AdjacencyRule and "
                            + (hops == null ? "no bridging path at all through the rest of the graph"
                                : "need " + hops + " hops to bridge, more than the allowed maximum (" + maxHops + ")")
                            + " - add a shorter path between them (a direct rule, or a rule to some third biome "
                            + "that's within " + maxHops + " hops of both)."
                    );
                }
            }
        }
    }

    private static Map<BiomeId, Integer> shortestHopsFrom(BiomeId start, List<BiomeId> ids, BiPredicate<BiomeId, BiomeId> isAllowed) {
        Map<BiomeId, Integer> hops = new HashMap<>();
        hops.put(start, 0);
        Deque<BiomeId> frontier = new ArrayDeque<>(List.of(start));
        while (!frontier.isEmpty()) {
            BiomeId current = frontier.poll();
            int nextHops = hops.get(current) + 1;
            for (BiomeId candidate : ids) {
                if (!candidate.equals(current) && isAllowed.test(current, candidate) && !hops.containsKey(candidate)) {
                    hops.put(candidate, nextHops);
                    frontier.add(candidate);
                }
            }
        }
        return hops;
    }
}
