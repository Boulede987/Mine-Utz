package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * CSP identity assignment over a {@link SeedGraph}: backtracking search with
 * a most-constrained-variable (MRV) heuristic and AC-3 constraint
 * propagation, on the abstract seed graph only — no geometry involved yet.
 * Deliberately adjacency-only (no size check), matching fix_dieas.md's own
 * Step 2 formulation; size is a geometry-stage concern. See
 * doc/Modelisation/ActivityDiagram/csp-identity-assignment.puml.
 *
 * <p>Fixed nodes' domains never change — propagation and backtracking only
 * ever touch free nodes. Deterministic: candidate identities are tried in
 * {@link BiomeId}'s natural order, and MRV ties break on the lower node id,
 * so the same {@link SeedGraph} always produces the same result (or the same
 * failure) — required for a world generator, where the same seed must
 * always produce the same world.
 */
public final class CspIdentityAssignment {

    private CspIdentityAssignment() {
    }

    /** Empty means the graph, as generated, has no legal coloring — the caller's reseed tier handles that, not this class. */
    public static Optional<Map<Integer, BiomeId>> solve(SeedGraph graph, BiomeGraph biomeGraph) {
        Map<Integer, List<NodeEdge>> edgesByNode = groupEdgesByNode(graph);
        Map<Integer, Set<BiomeId>> domains = initialDomains(graph);

        if (!ac3(domains, graph, biomeGraph, edgesByNode, new ArrayDeque<>(graph.edges()))) {
            return Optional.empty();
        }

        Map<Integer, BiomeId> assignment = new HashMap<>();
        for (SeedGraphNode node : graph.nodes()) {
            if (node.fixed()) {
                assignment.put(node.id(), node.fixedBiome());
            }
        }

        return backtrack(domains, assignment, graph, biomeGraph, edgesByNode);
    }

    private static Optional<Map<Integer, BiomeId>> backtrack(
        Map<Integer, Set<BiomeId>> domains,
        Map<Integer, BiomeId> assignment,
        SeedGraph graph,
        BiomeGraph biomeGraph,
        Map<Integer, List<NodeEdge>> edgesByNode
    ) {
        Optional<SeedGraphNode> nextNode = selectMostConstrainedUnassignedNode(graph, assignment, domains);
        if (nextNode.isEmpty()) {
            return Optional.of(Map.copyOf(assignment));
        }
        int nodeId = nextNode.get().id();

        for (BiomeId candidate : new TreeSet<>(domains.get(nodeId))) {
            Map<Integer, Set<BiomeId>> domainsAfterChoice = deepCopy(domains);
            domainsAfterChoice.put(nodeId, Set.of(candidate));
            assignment.put(nodeId, candidate);

            Deque<NodeEdge> propagationQueue = new ArrayDeque<>(edgesByNode.getOrDefault(nodeId, List.of()));
            if (ac3(domainsAfterChoice, graph, biomeGraph, edgesByNode, propagationQueue)) {
                Optional<Map<Integer, BiomeId>> result = backtrack(domainsAfterChoice, assignment, graph, biomeGraph, edgesByNode);
                if (result.isPresent()) {
                    return result;
                }
            }
            assignment.remove(nodeId);
        }
        return Optional.empty();
    }

    private static Optional<SeedGraphNode> selectMostConstrainedUnassignedNode(
        SeedGraph graph, Map<Integer, BiomeId> assignment, Map<Integer, Set<BiomeId>> domains
    ) {
        return graph.nodes().stream()
            .filter(node -> !node.fixed() && !assignment.containsKey(node.id()))
            .min(java.util.Comparator.<SeedGraphNode>comparingInt(node -> domains.get(node.id()).size())
                .thenComparingInt(SeedGraphNode::id));
    }

    /** Standard AC-3: revise every arc in the queue, re-enqueueing a node's arcs whenever its domain shrinks, until the queue empties or a domain is wiped out. */
    private static boolean ac3(
        Map<Integer, Set<BiomeId>> domains,
        SeedGraph graph,
        BiomeGraph biomeGraph,
        Map<Integer, List<NodeEdge>> edgesByNode,
        Deque<NodeEdge> queue
    ) {
        while (!queue.isEmpty()) {
            NodeEdge edge = queue.poll();
            if (reviseIfNeeded(domains, graph, biomeGraph, edge.a(), edge.b())) {
                if (domains.get(edge.a()).isEmpty()) {
                    return false;
                }
                queue.addAll(edgesByNode.getOrDefault(edge.a(), List.of()));
            }
            if (reviseIfNeeded(domains, graph, biomeGraph, edge.b(), edge.a())) {
                if (domains.get(edge.b()).isEmpty()) {
                    return false;
                }
                queue.addAll(edgesByNode.getOrDefault(edge.b(), List.of()));
            }
        }
        return true;
    }

    /** Removes every candidate from {@code targetId}'s domain that has no supporting value left in {@code againstId}'s domain. Returns whether anything was removed. */
    private static boolean reviseIfNeeded(
        Map<Integer, Set<BiomeId>> domains, SeedGraph graph, BiomeGraph biomeGraph, int targetId, int againstId
    ) {
        if (graph.node(targetId).fixed()) {
            return false;
        }
        Set<BiomeId> targetDomain = domains.get(targetId);
        Set<BiomeId> againstDomain = domains.get(againstId);

        Set<BiomeId> survivors = new LinkedHashSet<>();
        for (BiomeId candidate : targetDomain) {
            boolean hasSupport = againstDomain.stream().anyMatch(other -> biomeGraph.isAllowed(candidate, other));
            if (hasSupport) {
                survivors.add(candidate);
            }
        }
        if (survivors.size() == targetDomain.size()) {
            return false;
        }
        domains.put(targetId, survivors);
        return true;
    }

    private static Map<Integer, Set<BiomeId>> initialDomains(SeedGraph graph) {
        Map<Integer, Set<BiomeId>> domains = new HashMap<>();
        for (SeedGraphNode node : graph.nodes()) {
            domains.put(node.id(), new LinkedHashSet<>(node.domain()));
        }
        return domains;
    }

    private static Map<Integer, List<NodeEdge>> groupEdgesByNode(SeedGraph graph) {
        Map<Integer, List<NodeEdge>> edgesByNode = new HashMap<>();
        for (NodeEdge edge : graph.edges()) {
            edgesByNode.computeIfAbsent(edge.a(), key -> new ArrayList<>()).add(edge);
            edgesByNode.computeIfAbsent(edge.b(), key -> new ArrayList<>()).add(edge);
        }
        return edgesByNode;
    }

    private static Map<Integer, Set<BiomeId>> deepCopy(Map<Integer, Set<BiomeId>> domains) {
        Map<Integer, Set<BiomeId>> copy = new HashMap<>();
        domains.forEach((id, domain) -> copy.put(id, new LinkedHashSet<>(domain)));
        return copy;
    }
}
