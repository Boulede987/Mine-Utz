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
 * Same two-attempt promotion mechanics as {@link HexPromotionSimulator} (identity layer),
 * plus a SIZE dimension via {@link SizeAwareLocalClusterSolver}'s "self-adjacency as
 * clustering" model - see that class's own doc for why an independent size model, not the
 * live power-diagram mechanism, is the RIGHT choice for this package (2026-09-17 user
 * direction: this tool proves properties of the GRAPH, not of the specific live algorithm).
 *
 * <p>MIN is checked exactly once per query - right after the query's OWN target face is
 * promoted PERMANENT (irrevocable), its full same-biome cluster is computed; if that
 * cluster is SEALED ({@link ClusterAreaMath#isSealed} - every neighbor of every member
 * already decided, no room left to grow, ever) and its total area is still below the
 * biome's declared minimum, that's a real, genuine size dead end. A cluster that still has
 * open boundary is left alone - exactly TENTATIVE's own "still revisable" semantics
 * elsewhere in this project, just applied to size instead of identity.
 */
public final class SizeAwareHexPromotionSimulator {

    private final RawRuleTable table;
    private final int growthCapHexDistance;
    private final double baselineFaceAreaBlocks2;
    private final Map<AxialPos, FaceValue> faces = new LinkedHashMap<>();

    public SizeAwareHexPromotionSimulator(RawRuleTable table, int growthCapHexDistance, double baselineFaceAreaBlocks2) {
        if (growthCapHexDistance < 1) {
            throw new IllegalArgumentException("SizeAwareHexPromotionSimulator: growthCapHexDistance must be >= 1, got " + growthCapHexDistance);
        }
        this.table = table;
        this.growthCapHexDistance = growthCapHexDistance;
        this.baselineFaceAreaBlocks2 = baselineFaceAreaBlocks2;
    }

    public record FaceValue(BiomeId biomeId, boolean permanent) {
    }

    public Optional<FaceValue> valueAt(AxialPos face) {
        return Optional.ofNullable(faces.get(face));
    }

    public record QueryResult(boolean deadEnd, String message) {
    }

    public QueryResult query(AxialPos target) {
        return query(target, Map.of());
    }

    /** @param forcedAssignment steers the CSP toward specific target biomes for specific free faces, when legally/size reachable. */
    public QueryResult query(AxialPos target, Map<AxialPos, BiomeId> forcedAssignment) {
        FaceValue existing = faces.get(target);
        if (existing != null) {
            promoteToPermanent(target);
            return new QueryResult(false, null);
        }

        Set<AxialPos> attempt1 = growCluster(target, true);
        Optional<SizeAwareLocalClusterSolver.Solution> solved1 = SizeAwareLocalClusterSolver.solve(
            table, attempt1, this::committedBiomeAt, baselineFaceAreaBlocks2, forcedAssignment
        );
        if (solved1.isPresent()) {
            return commitAndCheck(attempt1, solved1.get().assignment(), target);
        }

        Set<AxialPos> attempt2 = growCluster(target, false);
        Optional<SizeAwareLocalClusterSolver.Solution> solved2 = SizeAwareLocalClusterSolver.solve(
            table, attempt2, this::committedBiomeAt, baselineFaceAreaBlocks2, forcedAssignment
        );
        if (solved2.isPresent()) {
            return commitAndCheck(attempt2, solved2.get().assignment(), target);
        }

        return new QueryResult(true,
            "SizeAwareHexPromotionSimulator.query: no legal identity+size assignment exists for the cluster seeded at " + target
                + ", even after pulling in every reachable tentative neighbor as free - a genuine dead end against only truly "
                + "PERMANENT context. attempt1FreeFaceCount=" + attempt1.size() + ", attempt2FreeFaceCount=" + attempt2.size());
    }

    private Optional<BiomeId> committedBiomeAt(AxialPos face) {
        FaceValue value = faces.get(face);
        return value == null ? Optional.empty() : Optional.of(value.biomeId());
    }

    private QueryResult commitAndCheck(Set<AxialPos> cluster, Map<AxialPos, BiomeId> assignment, AxialPos target) {
        for (AxialPos face : cluster) {
            faces.put(face, new FaceValue(assignment.get(face), false));
        }
        promoteToPermanent(target);

        BiomeId targetBiome = faces.get(target).biomeId();
        Set<AxialPos> finalCluster = ClusterAreaMath.clusterOf(target, targetBiome, Map.of(), this::committedBiomeAt);
        if (!ClusterAreaMath.isSealed(finalCluster, this::committedBiomeAt)) {
            return new QueryResult(false, null);
        }
        double widthBlocks = ClusterAreaMath.clusterWidthBlocks(finalCluster.size(), baselineFaceAreaBlocks2);
        int minWidthBlocks = table.sizeRangeOf(targetBiome).minWidthBlocks();
        if (widthBlocks < minWidthBlocks) {
            return new QueryResult(true,
                "SizeAwareHexPromotionSimulator.query: " + targetBiome + "'s cluster at " + target + " (" + finalCluster.size()
                    + " face(s)) is SEALED (no neighbor left undecided) with equivalent width " + widthBlocks
                    + " blocks, below its own declared minimum " + minWidthBlocks + " blocks - a genuine size dead end, "
                    + "not identity - this cluster can never grow any further.");
        }
        return new QueryResult(false, null);
    }

    private void promoteToPermanent(AxialPos face) {
        FaceValue current = faces.get(face);
        faces.put(face, new FaceValue(current.biomeId(), true));
    }

    /** Mirrors {@link HexPromotionSimulator}'s own growCluster exactly - only the free set is needed here, since {@link SizeAwareLocalClusterSolver} reads all committed context directly via {@link #committedBiomeAt}. */
    private Set<AxialPos> growCluster(AxialPos seed, boolean treatTentativeAsFixed) {
        Set<AxialPos> free = new LinkedHashSet<>();
        Set<AxialPos> visited = new HashSet<>();
        Deque<AxialPos> frontier = new ArrayDeque<>();
        visited.add(seed);
        frontier.add(seed);

        while (!frontier.isEmpty()) {
            AxialPos face = frontier.poll();
            FaceValue value = faces.get(face);
            if (value != null && value.permanent()) {
                continue;
            }
            if (treatTentativeAsFixed && value != null) {
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
        return free;
    }
}
