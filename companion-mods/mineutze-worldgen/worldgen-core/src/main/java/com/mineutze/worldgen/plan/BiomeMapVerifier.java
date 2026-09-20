package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks a rendered biome map directly, without trusting any theory about the warp that made it
 * (2026-09-20): every region's real area against its biome's size range, every pair of biomes
 * that touch against the legality table, every region's shared border against the borders its biome
 * requires, and - given the pattern's clusters - whether each cluster is still exactly one region
 * of about the same area.
 *
 * <p>A REGION is a connected set of same-biome samples (six-neighbor lattice, wrapping around the
 * tile). Area limits come from the biome's size range: the range is written as the width of an
 * equal-area hexagon, so its area is {@code 3*sqrt(3)/8 * width^2}. The tolerance is a fraction of the
 * biome's MINIMUM area, applied at both ends: a region may be that much smaller than the minimum or
 * that much larger than the maximum.
 */
final class BiomeMapVerifier {

    /** Neighbors of a sample that come "after" it, so every touching pair is visited once. */
    private static final int[][] FORWARD_NEIGHBORS = {{1, 0}, {0, 1}, {1, -1}};

    /** A region whose real area falls outside what its biome allows. */
    record AreaViolation(BiomeId biome, double areaBlocks2, double allowedMinBlocks2, double allowedMaxBlocks2) {
    }

    /** A region that shares less border with a biome it requires than its biome demands: how much it has and how much it needs, in blocks. */
    record BorderShortfall(BiomeId biome, BiomeId required, double lengthBlocks, double neededBlocks) {
    }

    /**
     * {@code illegalContactSamples} counts, per pair of biomes that may not touch, how many touching
     * sample pairs there are. {@code driftRatios} has one entry per region, its area divided by the area
     * its cells should cover, minus one. The last three fields describe whether the pattern's clusters
     * survived as regions; they are -1 when no clusters were given.
     */
    record Report(
        int componentCount,
        List<AreaViolation> areaViolations,
        Map<String, Integer> illegalContactSamples,
        List<BorderShortfall> borderShortfalls,
        int legalContactSamples,
        double[] driftRatios,
        int vanishedClusters,
        int splitClusters,
        int mergedComponents
    ) {
    }

    private BiomeMapVerifier() {
    }

    static Report verify(BiomeMap map, RawRuleTable table, double toleranceOfMinimum) {
        return verify(map, table, toleranceOfMinimum, null);
    }

    /** Without a length per required edge, so required borders are not checked: what tests of the other checks use. */
    static Report verify(BiomeMap map, RawRuleTable table, double toleranceOfMinimum, PatternClusters clusters) {
        return verify(map, table, toleranceOfMinimum, clusters, 0);
    }

    /**
     * {@code requiredBlocksPerEdge} is how many blocks of real border one required cell-edge counts for: a region whose biome requires N edges with
     * biome X must share at least {@code N * requiredBlocksPerEdge} blocks of border with X.
     */
    static Report verify(BiomeMap map, RawRuleTable table, double toleranceOfMinimum, PatternClusters clusters, double requiredBlocksPerEdge) {
        int size = map.size();
        int sampleCount = size * size;
        int[] parent = new int[sampleCount];
        for (int sample = 0; sample < sampleCount; sample++) {
            parent[sample] = sample;
        }
        boolean[][] legal = legalityBetweenBiomes(map, table);
        int biomeCount = map.biomes().size();
        int[] illegalContactCounts = new int[biomeCount * biomeCount];
        int legalContacts = 0;

        int[] biomeIndices = map.biomeIndices();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int sample = j * size + i;
                for (int[] step : FORWARD_NEIGHBORS) {
                    int neighbor = map.indexOf(i + step[0], j + step[1]);
                    if (biomeIndices[sample] == biomeIndices[neighbor]) {
                        union(parent, sample, neighbor);
                    } else if (legal[biomeIndices[sample]][biomeIndices[neighbor]]) {
                        legalContacts++;
                    } else {
                        int low = Math.min(biomeIndices[sample], biomeIndices[neighbor]);
                        int high = Math.max(biomeIndices[sample], biomeIndices[neighbor]);
                        illegalContactCounts[low * biomeCount + high]++;
                    }
                }
            }
        }

        int[] samplesInRegion = new int[sampleCount];
        for (int sample = 0; sample < sampleCount; sample++) {
            samplesInRegion[find(parent, sample)]++;
        }

        List<Integer> regionRoots = new ArrayList<>();
        for (int sample = 0; sample < sampleCount; sample++) {
            if (samplesInRegion[sample] > 0) {
                regionRoots.add(sample);
            }
        }

        List<AreaViolation> violations = areaViolations(map, table, toleranceOfMinimum, regionRoots, samplesInRegion);
        Map<String, Integer> illegalContacts = illegalContactsByName(map, illegalContactCounts);
        List<BorderShortfall> shortfalls = borderShortfalls(map, table, parent, regionRoots, requiredBlocksPerEdge);

        if (clusters == null) {
            return new Report(regionRoots.size(), violations, illegalContacts, shortfalls, legalContacts, new double[0], -1, -1, -1);
        }
        return withClusterComparison(map, regionRoots, samplesInRegion, parent, clusters, violations, illegalContacts, shortfalls, legalContacts);
    }

    private static Report withClusterComparison(
        BiomeMap map, List<Integer> regionRoots, int[] samplesInRegion, int[] parent, PatternClusters clusters,
        List<AreaViolation> violations, Map<String, Integer> illegalContacts, List<BorderShortfall> shortfalls, int legalContacts
    ) {
        int sampleCount = map.size() * map.size();
        int[] clusterOfRegion = new int[sampleCount];
        Arrays.fill(clusterOfRegion, -1);
        boolean[] regionIsMerged = new boolean[sampleCount];
        int[] cells = map.cells();
        for (int sample = 0; sample < sampleCount; sample++) {
            int root = find(parent, sample);
            int cluster = clusters.clusterOfCell()[cells[sample]];
            if (clusterOfRegion[root] == -1) {
                clusterOfRegion[root] = cluster;
            } else if (clusterOfRegion[root] != cluster) {
                regionIsMerged[root] = true;
            }
        }

        int[] regionsOfCluster = new int[clusters.clusterCount()];
        double[] drift = new double[regionRoots.size()];
        int mergedComponents = 0;
        double tileArea = map.sampleAreaBlocks2() * sampleCount;
        for (int index = 0; index < regionRoots.size(); index++) {
            int root = regionRoots.get(index);
            regionsOfCluster[clusterOfRegion[root]]++;
            if (regionIsMerged[root]) {
                mergedComponents++;
            }
            double expectedArea = clusters.cellsInCluster()[clusterOfRegion[root]] * tileArea / clusters.totalCells();
            drift[index] = samplesInRegion[root] * map.sampleAreaBlocks2() / expectedArea - 1;
        }
        int vanished = 0;
        int split = 0;
        for (int regions : regionsOfCluster) {
            if (regions == 0) {
                vanished++;
            } else if (regions > 1) {
                split++;
            }
        }
        return new Report(regionRoots.size(), violations, illegalContacts, shortfalls, legalContacts, drift, vanished, split, mergedComponents);
    }

    private static List<AreaViolation> areaViolations(BiomeMap map, RawRuleTable table, double toleranceOfMinimum, List<Integer> regionRoots, int[] samplesInRegion) {
        List<AreaViolation> violations = new ArrayList<>();
        for (int root : regionRoots) {
            BiomeId biome = map.biomes().get(map.biomeIndices()[root]);
            RawRuleTable.SizeRange range = table.sizeRangeOf(biome);
            double minimumArea = HexTorusGeometry.areaOfHexagonWidth(range.minWidthBlocks());
            double maximumArea = HexTorusGeometry.areaOfHexagonWidth(range.maxWidthBlocks());
            double allowedMinimum = minimumArea - toleranceOfMinimum * minimumArea;
            double allowedMaximum = maximumArea + toleranceOfMinimum * minimumArea;
            double area = samplesInRegion[root] * map.sampleAreaBlocks2();
            if (area < allowedMinimum || area > allowedMaximum) {
                violations.add(new AreaViolation(biome, area, allowedMinimum, allowedMaximum));
            }
        }
        return violations;
    }

    /**
     * For each region whose biome requires borders, how much border it really shares with each required biome. A border is measured by its contacts (touching sample pairs of the
     * six-neighbor lattice): a straight seam has about two contacts per sample spacing, so its length is {@code contacts * blocksPerSample / 2}.
     */
    private static List<BorderShortfall> borderShortfalls(BiomeMap map, RawRuleTable table, int[] parent, List<Integer> regionRoots, double requiredBlocksPerEdge) {
        int biomeCount = map.biomes().size();
        int[][] minEdges = new int[biomeCount][biomeCount];
        boolean anyRequirement = false;
        for (int biome = 0; biome < biomeCount; biome++) {
            for (Map.Entry<BiomeId, Integer> requirement : table.requiredNeighborsOf(map.biomes().get(biome)).entrySet()) {
                minEdges[biome][map.biomes().indexOf(requirement.getKey())] = requirement.getValue();
                anyRequirement = true;
            }
        }
        if (!anyRequirement) {
            return List.of();
        }

        Map<Long, Integer> contactsByRegionAndBiome = new HashMap<>();
        int size = map.size();
        int[] biomeIndices = map.biomeIndices();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int sample = j * size + i;
                for (int[] step : FORWARD_NEIGHBORS) {
                    int neighbor = map.indexOf(i + step[0], j + step[1]);
                    int here = biomeIndices[sample];
                    int there = biomeIndices[neighbor];
                    if (here == there) {
                        continue;
                    }
                    if (minEdges[here][there] > 0) {
                        contactsByRegionAndBiome.merge((long) find(parent, sample) * biomeCount + there, 1, Integer::sum);
                    }
                    if (minEdges[there][here] > 0) {
                        contactsByRegionAndBiome.merge((long) find(parent, neighbor) * biomeCount + here, 1, Integer::sum);
                    }
                }
            }
        }

        List<BorderShortfall> shortfalls = new ArrayList<>();
        for (int root : regionRoots) {
            int biome = biomeIndices[root];
            for (int required = 0; required < biomeCount; required++) {
                if (minEdges[biome][required] == 0) {
                    continue;
                }
                double length = contactsByRegionAndBiome.getOrDefault((long) root * biomeCount + required, 0) * map.blocksPerSample() / 2;
                double needed = minEdges[biome][required] * requiredBlocksPerEdge;
                if (length < needed) {
                    shortfalls.add(new BorderShortfall(map.biomes().get(biome), map.biomes().get(required), length, needed));
                }
            }
        }
        return shortfalls;
    }

    private static boolean[][] legalityBetweenBiomes(BiomeMap map, RawRuleTable table) {
        int biomeCount = map.biomes().size();
        boolean[][] legal = new boolean[biomeCount][biomeCount];
        for (int first = 0; first < biomeCount; first++) {
            for (int second = 0; second < biomeCount; second++) {
                legal[first][second] = first == second || table.isLegal(map.biomes().get(first), map.biomes().get(second));
            }
        }
        return legal;
    }

    private static Map<String, Integer> illegalContactsByName(BiomeMap map, int[] illegalContactCounts) {
        int biomeCount = map.biomes().size();
        Map<String, Integer> named = new TreeMap<>();
        for (int low = 0; low < biomeCount; low++) {
            for (int high = low + 1; high < biomeCount; high++) {
                int count = illegalContactCounts[low * biomeCount + high];
                if (count > 0) {
                    BiomeId first = map.biomes().get(low);
                    BiomeId second = map.biomes().get(high);
                    boolean firstIsLower = first.compareTo(second) <= 0;
                    named.merge((firstIsLower ? first : second) + " | " + (firstIsLower ? second : first), count, Integer::sum);
                }
            }
        }
        return named;
    }

    private static int find(int[] parent, int sample) {
        int root = sample;
        while (parent[root] != root) {
            parent[root] = parent[parent[root]];
            root = parent[root];
        }
        return root;
    }

    private static void union(int[] parent, int first, int second) {
        int firstRoot = find(parent, first);
        int secondRoot = find(parent, second);
        if (firstRoot != secondRoot) {
            parent[Math.max(firstRoot, secondRoot)] = Math.min(firstRoot, secondRoot);
        }
    }
}
