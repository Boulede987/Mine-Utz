package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Size slack of the biomes a failed pattern is stuck on (2026-10-03): those on an illegal edge, and
 * those with no cells at all. The search scores sizes per same-biome cluster, so the slack is per
 * cluster: a biome's slack is its worst cluster's distance to the nearer end of its cell range,
 * negative when a cluster lies outside that range. A missing biome's slack is minus its minimum.
 * Ties go to the narrower range, and only the first {@link #LINES_SHOWN} are listed.
 */
final class SizeSlackReport {

    static final int LINES_SHOWN = 15;

    private record Line(int slack, int rangeWidth, String text) {
    }

    private SizeSlackReport() {
    }

    static String of(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Map<ToroidalPos, BiomeId> pattern) {
        Map<BiomeId, List<Integer>> cellCountsByBiome = PatternClusters.of(grid, pattern).cellCountsByBiome(grid, pattern);
        Set<BiomeId> stuck = ToroidalSizedLocalSearch.biomesOnIllegalEdges(table, grid, pattern);
        List<Line> lines = table.allBiomeIds().stream()
            .filter(biome -> stuck.contains(biome) || !cellCountsByBiome.containsKey(biome))
            .map(biome -> lineOf(model, biome, cellCountsByBiome.getOrDefault(biome, List.of())))
            .sorted(Comparator.comparingInt(Line::slack).thenComparingInt(Line::rangeWidth))
            .toList();
        if (lines.isEmpty()) {
            return "\n  no biome is on an illegal edge or missing";
        }
        StringBuilder report = new StringBuilder();
        lines.stream().limit(LINES_SHOWN).forEach(line -> report.append("\n  ").append(line.text()));
        if (lines.size() > LINES_SHOWN) {
            report.append("\n  ... and ").append(lines.size() - LINES_SHOWN).append(" more such biomes with slack at or above ").append(lines.get(LINES_SHOWN).slack());
        }
        return report.toString();
    }

    private static Line lineOf(ClusterSizeModel model, BiomeId biome, List<Integer> clusterCounts) {
        int minCells = model.minCells(biome);
        int maxCells = model.maxCells(biome);
        int rangeWidth = maxCells - minCells;
        String allowed = "needs " + minCells + ".." + maxCells + " cells per cluster";
        if (clusterCounts.isEmpty()) {
            return new Line(-minCells, rangeWidth, biome + ": MISSING, " + allowed);
        }
        int worstSlack = Integer.MAX_VALUE;
        int worstCount = 0;
        for (int cellCount : clusterCounts) {
            int slack = Math.min(cellCount - minCells, maxCells - cellCount);
            if (slack < worstSlack) {
                worstSlack = slack;
                worstCount = cellCount;
            }
        }
        return new Line(
            worstSlack, rangeWidth,
            biome + ": slack " + worstSlack + " (" + clusterCounts.size() + " clusters, worst has " + worstCount + " cells; " + allowed + ")"
        );
    }
}
