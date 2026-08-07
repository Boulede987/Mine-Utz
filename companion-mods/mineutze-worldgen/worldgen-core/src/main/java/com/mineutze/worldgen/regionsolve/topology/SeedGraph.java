package com.mineutze.worldgen.regionsolve.topology;

import java.util.List;
import java.util.Set;

/**
 * The abstract seed adjacency graph for one solve-region, before any
 * geometry exists — topology and per-node candidate identities only. See
 * doc/Modelisation/ActivityDiagram/seed-graph-generation.puml.
 *
 * @param reseedRecommended true when construction already found a free node
 *                           with an empty domain — CSP identity assignment
 *                           would fail immediately (no legal identity fits
 *                           the fixed neighbors this scatter happened to
 *                           touch it with), so the caller should discard
 *                           this graph and regenerate the scatter rather
 *                           than even attempt to solve it
 */
public record SeedGraph(List<SeedGraphNode> nodes, Set<NodeEdge> edges, boolean reseedRecommended) {

    public SeedGraphNode node(int id) {
        return nodes.get(id);
    }
}
