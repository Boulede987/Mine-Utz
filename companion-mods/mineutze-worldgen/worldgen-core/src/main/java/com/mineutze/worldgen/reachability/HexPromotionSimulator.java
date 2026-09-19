package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.coherence.RawRuleTable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Faithful abstraction of the real live promotion/commitment mechanics - mirrors {@code
 * TriangleFieldGeometryRealization.realizeCluster}'s own state machine exactly: undecided ->
 * tentative -> permanent per face, two-attempt cluster growth (attempt 1 stops at ANY valued
 * neighbor - tentative treated as a wall too; attempt 2, only tried if attempt 1's joint CSP
 * fails, stops only at PERMANENT, freeing tentative neighbors instead), joint CSP solved via
 * {@link LocalClusterSolver} against {@link RawRuleTable}'s real adjacency-legality data.
 *
 * <p><b>Deliberately NOT modeled</b> (see package doc for the independence boundary): no real
 * geometry (mesh building, vertex placement, polygon rasterizing), no size/area layer, no
 * vertex-triple promotion - this works purely at face-IDENTITY granularity. A query here
 * targets a face DIRECTLY by {@link AxialPos}, not a raw (x,z) point routed through nearest-
 * face lookup + polygon containment - loses only "which exact face a point falls in," not the
 * promotion logic itself. Growth is bounded by {@code growthCapHexDistance} (hex-ring count
 * from the query's own seed) - the abstract stand-in for the real {@code
 * CLUSTER_GROWTH_CAP_RADIUS_BLOCKS}/{@code HEX_SIZE_BLOCKS} ratio (~7.7 rings at production
 * values, see {@code TriangleFieldGeometryRealization}'s own constants).
 */
public final class HexPromotionSimulator {

    private final RawRuleTable table;
    private final int growthCapHexDistance;
    private final Map<AxialPos, FaceValue> faces = new LinkedHashMap<>();

    public HexPromotionSimulator(RawRuleTable table, int growthCapHexDistance) {
        if (growthCapHexDistance < 1) {
            throw new IllegalArgumentException("HexPromotionSimulator: growthCapHexDistance must be >= 1, got " + growthCapHexDistance);
        }
        this.table = table;
        this.growthCapHexDistance = growthCapHexDistance;
    }

    public record FaceValue(BiomeId biomeId, boolean permanent) {
    }

    public Optional<FaceValue> valueAt(AxialPos face) {
        return Optional.ofNullable(faces.get(face));
    }

    /** One query's full outcome - mirrors {@code TriangleFieldGeometryRealization.realizeCluster}'s own exception fields, real diagnostic parity with the live crash messages. */
    public record QueryResult(
        boolean deadEnd, String message, int attempt1FreeFaceCount, int attempt1FixedFaceCount, int attempt2FreeFaceCount, int attempt2FixedFaceCount
    ) {
    }

    public QueryResult query(AxialPos target) {
        return query(target, Map.of());
    }

    /** @param forcedAssignment steers the CSP toward specific target biomes for specific free faces, when legally reachable - see {@link LocalClusterSolver#solve(RawRuleTable, Set, Map, Map)}. */
    public QueryResult query(AxialPos target, Map<AxialPos, BiomeId> forcedAssignment) {
        FaceValue existing = faces.get(target);
        if (existing != null) {
            promoteToPermanent(target);
            return new QueryResult(false, null, 0, 0, 0, 0);
        }

        ClusterGrowth attempt1 = growCluster(target, true);
        Optional<Map<AxialPos, BiomeId>> solved1 = LocalClusterSolver.solve(table, attempt1.free(), attempt1.fixedBiomes(), forcedAssignment);
        if (solved1.isPresent()) {
            commit(attempt1.free(), solved1.get(), target);
            return new QueryResult(false, null, attempt1.free().size(), attempt1.fixedBiomes().size(), 0, 0);
        }

        ClusterGrowth attempt2 = growCluster(target, false);
        Optional<Map<AxialPos, BiomeId>> solved2 = LocalClusterSolver.solve(table, attempt2.free(), attempt2.fixedBiomes(), forcedAssignment);
        if (solved2.isPresent()) {
            commit(attempt2.free(), solved2.get(), target);
            return new QueryResult(false, null, attempt1.free().size(), attempt1.fixedBiomes().size(), attempt2.free().size(), attempt2.fixedBiomes().size());
        }

        String message = "HexPromotionSimulator.query: no legal identity assignment exists for the cluster seeded at " + target
            + ", even after pulling in every reachable tentative neighbor as free - a genuine dead end against only truly "
            + "PERMANENT context. attempt1FreeFaceCount=" + attempt1.free().size() + ", attempt1FixedFaceCount=" + attempt1.fixedBiomes().size()
            + ", attempt2FreeFaceCount=" + attempt2.free().size() + ", attempt2FixedFaceCount=" + attempt2.fixedBiomes().size()
            + ", attempt2FixedFaceBiomes=" + attempt2.fixedBiomes();
        return new QueryResult(true, message, attempt1.free().size(), attempt1.fixedBiomes().size(), attempt2.free().size(), attempt2.fixedBiomes().size());
    }

    private void promoteToPermanent(AxialPos face) {
        FaceValue current = faces.get(face);
        faces.put(face, new FaceValue(current.biomeId(), true));
    }

    private void commit(Set<AxialPos> cluster, Map<AxialPos, BiomeId> assignment, AxialPos target) {
        for (AxialPos face : cluster) {
            faces.put(face, new FaceValue(assignment.get(face), false));
        }
        promoteToPermanent(target);
    }

    private record ClusterGrowth(Set<AxialPos> free, Map<AxialPos, BiomeId> fixedBiomes) {
    }

    /** Mirrors {@code TriangleFieldGeometryRealization.growCluster}'s own BFS exactly, real check order included (permanent wall, then tentative-as-fixed if attempt 1, then growth cap). */
    private ClusterGrowth growCluster(AxialPos seed, boolean treatTentativeAsFixed) {
        Set<AxialPos> free = new LinkedHashSet<>();
        Map<AxialPos, BiomeId> fixedBiomes = new LinkedHashMap<>();
        Set<AxialPos> visited = new HashSet<>();
        Deque<AxialPos> frontier = new ArrayDeque<>();
        visited.add(seed);
        frontier.add(seed);

        while (!frontier.isEmpty()) {
            AxialPos face = frontier.poll();
            FaceValue value = faces.get(face);
            if (value != null && value.permanent()) {
                fixedBiomes.put(face, value.biomeId());
                continue;
            }
            if (treatTentativeAsFixed && value != null) {
                fixedBiomes.put(face, value.biomeId());
                continue;
            }
            if (seed.distanceTo(face) >= growthCapHexDistance) {
                continue;
            }
            free.add(face);
            for (AxialPos neighbor : face.neighbors()) {
                if (visited.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
        return new ClusterGrowth(free, fixedBiomes);
    }
}
