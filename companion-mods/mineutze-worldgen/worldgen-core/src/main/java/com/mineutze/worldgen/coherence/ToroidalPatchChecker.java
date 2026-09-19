package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real, independent verification of a fully-built {@link ToroidalPatchConstruction.BuildResult}
 * - identity legality on EVERY real grid edge (re-derived here, not trusted from
 * construction-time checks alone - same discipline as {@link ConstraintChecker}), every
 * same-biome cluster's real width against its declared range, and full-catalog coverage.
 *
 * <p>Does NOT check required-neighbor minimums (honest scope limit for this first
 * prototype, see {@link ToroidalPatchConstruction}'s own doc on why MIN pursuit itself is
 * also deferred here).
 */
final class ToroidalPatchChecker {

    private ToroidalPatchChecker() {
    }

    record Result(List<String> illegalAdjacencies, List<String> outOfRangeClusters, List<BiomeId> missingBiomes) {
        boolean isValid() {
            return illegalAdjacencies.isEmpty() && outOfRangeClusters.isEmpty() && missingBiomes.isEmpty();
        }
    }

    static Result check(RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, double baselineFaceAreaBlocks2) {
        List<String> illegalAdjacencies = new ArrayList<>();
        for (Map.Entry<ToroidalPos, BiomeId> entry : assignment.entrySet()) {
            ToroidalPos pos = entry.getKey();
            BiomeId biome = entry.getValue();
            for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (neighborBiome != null && !biome.equals(neighborBiome) && !table.isLegal(biome, neighborBiome)) {
                    illegalAdjacencies.add(pos + "(" + biome + ") <-> " + neighbor + "(" + neighborBiome + ")");
                }
            }
        }

        List<String> outOfRangeClusters = new ArrayList<>();
        Set<ToroidalPos> visited = new HashSet<>();
        for (Map.Entry<ToroidalPos, BiomeId> entry : assignment.entrySet()) {
            ToroidalPos pos = entry.getKey();
            if (visited.contains(pos)) {
                continue;
            }
            BiomeId biome = entry.getValue();
            Set<ToroidalPos> cluster = ToroidalClusterMath.clusterOf(grid, pos, biome, assignment);
            visited.addAll(cluster);
            double widthBlocks = ToroidalClusterMath.clusterWidthBlocks(cluster.size(), baselineFaceAreaBlocks2);
            RawRuleTable.SizeRange range = table.sizeRangeOf(biome);
            if (widthBlocks < range.minWidthBlocks() || widthBlocks > range.maxWidthBlocks()) {
                outOfRangeClusters.add(
                    biome + " cluster at " + pos + " (" + cluster.size() + " cells, width " + widthBlocks + ") outside declared ["
                        + range.minWidthBlocks() + "," + range.maxWidthBlocks() + "]"
                );
            }
        }

        List<BiomeId> missingBiomes = table.allBiomeIds().stream().filter(id -> !assignment.containsValue(id)).toList();

        return new Result(illegalAdjacencies, outOfRangeClusters, missingBiomes);
    }
}
