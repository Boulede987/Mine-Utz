package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeNode;
import com.mineutze.worldgen.regionsolve.MacroCellPos;
import com.mineutze.worldgen.regionsolve.RegionSolveInput;
import com.mineutze.worldgen.regionsolve.SeedPlacer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the abstract seed graph for one solve-region: add a fixed node for
 * every already-cached boundary cell, scatter interior seeds
 * ({@link SeedPlacer}'s existing deterministic jittered grid — no new
 * placement scheme needed), triangulate for adjacency, then narrow each free
 * node's domain by whichever fixed neighbors it turned out to touch. See
 * doc/Modelisation/ActivityDiagram/seed-graph-generation.puml.
 */
public final class SeedGraphBuilder {

    // Folded into the scatter's own seed so a reseed attempt actually produces a
    // different scatter (SeedPlacer is otherwise fully deterministic in worldSeed)
    // while staying fully reproducible for a given (worldSeed, region, attempt).
    private static final long RESEED_ATTEMPT_SALT = 0x5C47_7E12L;

    private SeedGraphBuilder() {
    }

    public static SeedGraph build(RegionSolveInput input, int seedSpacingBlocks) {
        return build(input, seedSpacingBlocks, 0, Set.of());
    }

    /**
     * @param reseedAttempt          which retry of the interior scatter this is — 0 the first time, incremented by {@code TopologyFirstRegionSolver}'s reseed tier on failure
     * @param excludedSeedPositions  positions dropped by {@code TopologyFirstRegionSolver}'s node-drop tier — never turned into a node, regardless of what this attempt's scatter would otherwise place there
     */
    public static SeedGraph build(RegionSolveInput input, int seedSpacingBlocks, int reseedAttempt, Set<MacroCellPos> excludedSeedPositions) {
        List<SeedGraphNode> nodes = new ArrayList<>();
        List<Point2D> points = new ArrayList<>();

        addFixedBoundaryNodes(input, nodes, points);
        addInteriorSeedNodes(input, seedSpacingBlocks, reseedAttempt, excludedSeedPositions, nodes, points);

        Set<NodeEdge> edges = DelaunayTriangulation.triangulate(points);
        List<SeedGraphNode> narrowedNodes = narrowFreeDomains(input.graph(), nodes, edges);

        boolean reseedRecommended = narrowedNodes.stream()
            .anyMatch(node -> !node.fixed() && node.domain().isEmpty());

        return new SeedGraph(narrowedNodes, edges, reseedRecommended);
    }

    private static void addFixedBoundaryNodes(RegionSolveInput input, List<SeedGraphNode> nodes, List<Point2D> points) {
        input.fixedBoundaryCells().entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(MacroCellPos::x).thenComparingInt(MacroCellPos::z)))
            .forEach(entry -> {
                int id = nodes.size();
                nodes.add(SeedGraphNode.fixed(id, entry.getKey(), entry.getValue()));
                points.add(toPoint(entry.getKey()));
            });
    }

    private static void addInteriorSeedNodes(
        RegionSolveInput input, int seedSpacingBlocks, int reseedAttempt, Set<MacroCellPos> excludedSeedPositions, List<SeedGraphNode> nodes, List<Point2D> points
    ) {
        Set<BiomeId> allBiomeIds = input.graph().nodes().stream()
            .map(BiomeNode::id)
            .collect(Collectors.toUnmodifiableSet());

        long scatterSeed = input.worldSeed() ^ (reseedAttempt * RESEED_ATTEMPT_SALT);
        List<MacroCellPos> interiorSeeds = SeedPlacer.placeSeeds(input.region(), input.gridConfig(), scatterSeed, seedSpacingBlocks);
        for (MacroCellPos seed : interiorSeeds) {
            if (excludedSeedPositions.contains(seed)) {
                continue;
            }
            int id = nodes.size();
            nodes.add(SeedGraphNode.free(id, seed, allBiomeIds));
            points.add(toPoint(seed));
        }
    }

    private static List<SeedGraphNode> narrowFreeDomains(BiomeGraph graph, List<SeedGraphNode> nodes, Set<NodeEdge> edges) {
        Map<Integer, List<Integer>> neighborsByNode = buildAdjacency(edges);

        List<SeedGraphNode> narrowed = new ArrayList<>(nodes.size());
        for (SeedGraphNode node : nodes) {
            if (node.fixed()) {
                narrowed.add(node);
                continue;
            }
            narrowed.add(narrowFreeNode(graph, node, nodes, neighborsByNode.getOrDefault(node.id(), List.of())));
        }
        return narrowed;
    }

    private static SeedGraphNode narrowFreeNode(BiomeGraph graph, SeedGraphNode node, List<SeedGraphNode> nodes, List<Integer> neighborIds) {
        Set<BiomeId> domain = new HashSet<>(node.domain());
        for (int neighborId : neighborIds) {
            SeedGraphNode neighbor = nodes.get(neighborId);
            if (!neighbor.fixed()) {
                continue;
            }
            BiomeId fixedNeighborBiome = neighbor.fixedBiome();
            domain.removeIf(candidate -> !graph.isAllowed(candidate, fixedNeighborBiome));
        }
        return SeedGraphNode.free(node.id(), node.position(), Set.copyOf(domain));
    }

    private static Map<Integer, List<Integer>> buildAdjacency(Set<NodeEdge> edges) {
        Map<Integer, List<Integer>> neighbors = new HashMap<>();
        for (NodeEdge edge : edges) {
            neighbors.computeIfAbsent(edge.a(), key -> new ArrayList<>()).add(edge.b());
            neighbors.computeIfAbsent(edge.b(), key -> new ArrayList<>()).add(edge.a());
        }
        return neighbors;
    }

    private static Point2D toPoint(MacroCellPos pos) {
        return new Point2D(pos.x(), pos.z());
    }
}
