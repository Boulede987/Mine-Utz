package com.mineutze.worldgen.reachability;

import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.regionsolve.topology.HexGeometry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * BFS over contiguous same-biome faces - "self-adjacency as clustering," the SAME real
 * mechanism {@code TriangleFieldGeometryRealization}'s own class doc names as the hex
 * mesh's real, non-implementation-specific size mechanism (a biome's declared total size is
 * the sum of a contiguous same-biome cluster's faces), reused here independent of the live
 * power-diagram code (see {@link SizeAwareLocalClusterSolver}'s own doc for why this is the
 * right choice for a GRAPH-level tool, not a compromise).
 *
 * <p>Used both DURING search ({@code provisionalAssignment} mixes in-progress backtracking
 * choices with already-committed context) and AFTER commit ({@code provisionalAssignment}
 * empty, everything real).
 */
final class ClusterAreaMath {

    private ClusterAreaMath() {
    }

    static Set<AxialPos> clusterOf(
        AxialPos start, BiomeId biome, Map<AxialPos, BiomeId> provisionalAssignment, Function<AxialPos, Optional<BiomeId>> committedBiomeAt
    ) {
        Set<AxialPos> visited = new LinkedHashSet<>();
        Deque<AxialPos> frontier = new ArrayDeque<>();
        visited.add(start);
        frontier.add(start);
        while (!frontier.isEmpty()) {
            AxialPos face = frontier.poll();
            for (AxialPos neighbor : face.neighbors()) {
                if (visited.contains(neighbor)) {
                    continue;
                }
                BiomeId neighborBiome = provisionalAssignment.get(neighbor);
                if (neighborBiome == null) {
                    neighborBiome = committedBiomeAt.apply(neighbor).orElse(null);
                }
                if (biome.equals(neighborBiome)) {
                    visited.add(neighbor);
                    frontier.add(neighbor);
                }
            }
        }
        return visited;
    }

    static int clusterFaceCount(
        AxialPos start, BiomeId biome, Map<AxialPos, BiomeId> provisionalAssignment, Function<AxialPos, Optional<BiomeId>> committedBiomeAt
    ) {
        return clusterOf(start, biome, provisionalAssignment, committedBiomeAt).size();
    }

    /**
     * A {@code faceCount}-face cluster's EQUIVALENT WIDTH, comparable directly against
     * {@link com.mineutze.worldgen.coherence.RawRuleTable.SizeRange}'s own {@code
     * minWidthBlocks}/{@code maxWidthBlocks} - real, necessary conversion (2026-09-17 fix):
     * area is what's truly additive across a cluster (a face's own real area, summed), but
     * the catalog's declared bounds are WIDTH values (same convention {@link HexGeometry}
     * and production's own {@code AchievableWidthRange} use) - comparing raw summed AREA
     * directly against a WIDTH bound is a units mismatch that made every real cluster look
     * catastrophically oversized (one single face's own real area alone, ~274,000 blocks²
     * at production's own 325-circumradius baseline, already dwarfs every real catalog
     * biome's declared max of a few hundred to ~1500). Width grows with the SQUARE ROOT of
     * area, not linearly with face count - a cluster of 2-3 faces reaches meaningfully
     * further past one face's own baseline width, not "2-3x" further.
     */
    static double clusterWidthBlocks(int faceCount, double baselineFaceAreaBlocks2) {
        double totalAreaBlocks2 = faceCount * baselineFaceAreaBlocks2;
        return HexGeometry.widthForCircumradius(HexGeometry.circumradiusForArea(totalAreaBlocks2));
    }

    /** True if EVERY neighbor of EVERY cluster member is already decided (any biome, including outside the cluster) - no room left to grow, a real irrevocable seal. */
    static boolean isSealed(Set<AxialPos> cluster, Function<AxialPos, Optional<BiomeId>> committedBiomeAt) {
        for (AxialPos face : cluster) {
            for (AxialPos neighbor : face.neighbors()) {
                if (!cluster.contains(neighbor) && committedBiomeAt.apply(neighbor).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }
}
