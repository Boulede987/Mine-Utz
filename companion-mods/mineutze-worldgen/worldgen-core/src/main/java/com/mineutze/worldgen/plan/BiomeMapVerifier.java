package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleFunction;

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

    /** A region whose real borders do not meet the requirement of its biome, written as text. */
    record BorderShortfall(BiomeId biome, String requirement) {
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

    /** Which samples fall in the same region as each other, and how many samples that region has - the shared output of the union-find pass every other check reads from. */
    private record RegionRoots(int[] samplesInRegion, List<Integer> roots) {
    }

    /** How many touching sample pairs were illegal (by biome pair, {@code low * biomeCount + high}), and how many were legal overall. */
    private record ContactTally(int[] illegalContactCounts, int legalContacts) {
    }

    /** Which pattern cluster each region's samples came from, and whether a region's samples disagreed on that (came from more than one cluster). */
    private record RegionClusters(int[] clusterOfRegion, boolean[] regionIsMerged) {
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
        int sampleCount = map.size() * map.size();
        UnionFind regions = new UnionFind(sampleCount);
        boolean[][] legal = legalityBetweenBiomes(map, table);
        ContactTally contacts = scanContacts(map, legal, regions);
        RegionRoots regionRoots = regionRootsOf(regions, sampleCount);

        List<AreaViolation> violations = areaViolations(map, table, toleranceOfMinimum, regionRoots);
        Map<String, Integer> illegalContacts = illegalContactsByName(map, contacts.illegalContactCounts());
        List<BorderShortfall> shortfalls = borderShortfalls(map, table, regions, regionRoots.roots(), requiredBlocksPerEdge);

        if (clusters == null) {
            return new Report(regionRoots.roots().size(), violations, illegalContacts, shortfalls, contacts.legalContacts(), new double[0], -1, -1, -1);
        }
        return withClusterComparison(map, regionRoots, regions, clusters, violations, illegalContacts, shortfalls, contacts.legalContacts());
    }

    // ---- region finding (one pass: unions same-biome neighbors, tallies illegal contacts) ----

    /** One pass over every touching sample pair: unions same-biome pairs into {@code regions}, tallies contacts between biomes that may not touch. */
    private static ContactTally scanContacts(BiomeMap map, boolean[][] legal, UnionFind regions) {
        int biomeCount = map.biomes().size();
        int[] illegalContactCounts = new int[biomeCount * biomeCount];
        int legalContacts = 0;
        int size = map.size();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int sample = j * size + i;
                for (int[] step : FORWARD_NEIGHBORS) {
                    int neighbor = map.indexOf(i + step[0], j + step[1]);
                    legalContacts += recordContactAndUnion(map, legal, regions, illegalContactCounts, biomeCount, sample, neighbor);
                }
            }
        }
        return new ContactTally(illegalContactCounts, legalContacts);
    }

    /** @return 1 if the pair was a legal contact between different biomes, 0 otherwise (same biome, unioned instead; or illegal, tallied instead). */
    private static int recordContactAndUnion(BiomeMap map, boolean[][] legal, UnionFind regions, int[] illegalContactCounts, int biomeCount, int sample, int neighbor) {
        int[] biomeIndices = map.biomeIndices();
        int hereBiome = biomeIndices[sample];
        int thereBiome = biomeIndices[neighbor];
        if (hereBiome == thereBiome) {
            regions.union(sample, neighbor);
            return 0;
        }
        if (legal[hereBiome][thereBiome]) {
            return 1;
        }
        int low = Math.min(hereBiome, thereBiome);
        int high = Math.max(hereBiome, thereBiome);
        illegalContactCounts[low * biomeCount + high]++;
        return 0;
    }

    private static RegionRoots regionRootsOf(UnionFind regions, int sampleCount) {
        int[] samplesInRegion = new int[sampleCount];
        for (int sample = 0; sample < sampleCount; sample++) {
            samplesInRegion[regions.find(sample)]++;
        }
        List<Integer> roots = new ArrayList<>();
        for (int sample = 0; sample < sampleCount; sample++) {
            checkRootForInclusion(samplesInRegion, roots, sample);
        }
        return new RegionRoots(samplesInRegion, roots);
    }

    private static void checkRootForInclusion(int[] samplesInRegion, List<Integer> roots, int sample) {
        if (samplesInRegion[sample] > 0) {
            roots.add(sample);
        }
    }

    // ---- area check ----

    private record AllowedArea(double minimum, double maximum) {
    }

    private static AllowedArea allowedArea(RawRuleTable.SizeRange range, double toleranceOfMinimum) {
        double minimumArea = HexTorusGeometry.areaOfHexagonWidth(range.minWidthBlocks());
        double maximumArea = HexTorusGeometry.areaOfHexagonWidth(range.maxWidthBlocks());
        return new AllowedArea(minimumArea - toleranceOfMinimum * minimumArea, maximumArea + toleranceOfMinimum * minimumArea);
    }

    private static List<AreaViolation> areaViolations(BiomeMap map, RawRuleTable table, double toleranceOfMinimum, RegionRoots regionRoots) {
        List<AreaViolation> violations = new ArrayList<>();
        for (int root : regionRoots.roots()) {
            checkRegionArea(map, table, toleranceOfMinimum, regionRoots, violations, root);
        }
        return violations;
    }

    private static void checkRegionArea(BiomeMap map, RawRuleTable table, double toleranceOfMinimum, RegionRoots regionRoots, List<AreaViolation> violations, int root) {
        BiomeId biome = map.biomes().get(map.biomeIndices()[root]);
        AllowedArea allowed = allowedArea(table.sizeRangeOf(biome), toleranceOfMinimum);
        double area = regionRoots.samplesInRegion()[root] * map.sampleAreaBlocks2();
        if (area < allowed.minimum() || area > allowed.maximum()) {
            violations.add(new AreaViolation(biome, area, allowed.minimum(), allowed.maximum()));
        }
    }

    // ---- required-border check ----

    /**
     * The regions whose borders do not meet the requirement of their biome. A border is measured by its contacts (touching sample pairs of the
     * six-neighbor lattice): a straight seam has about two contacts per sample spacing, so its length is {@code contacts * blocksPerSample / 2}.
     */
    private static List<BorderShortfall> borderShortfalls(BiomeMap map, RawRuleTable table, UnionFind regions, List<Integer> regionRoots, double requiredBlocksPerEdge) {
        List<ResolvedBorderRequirement> requirementOfBiome = requirementsByBiomeIndex(map, table);
        if (requirementOfBiome.stream().noneMatch(requirement -> requirement != null)) {
            return List.of();
        }
        Map<Long, Integer> contactsByRegionAndBiome = tallyContactsWithRequiredBiomes(map, regions, requirementOfBiome);
        return shortfallsOf(map, regionRoots, requirementOfBiome, contactsByRegionAndBiome, requiredBlocksPerEdge);
    }

    private static List<ResolvedBorderRequirement> requirementsByBiomeIndex(BiomeMap map, RawRuleTable table) {
        List<ResolvedBorderRequirement> requirementOfBiome = new ArrayList<>();
        for (BiomeId biome : map.biomes()) {
            requirementOfBiome.add(table.requirementOf(biome).orElse(null));
        }
        return requirementOfBiome;
    }

    /** For every touching sample pair where either side's biome has a requirement, how many edges the OTHER side's region shares with it, keyed as {@code regionRoot * biomeCount + otherBiomeIndex}. */
    private static Map<Long, Integer> tallyContactsWithRequiredBiomes(BiomeMap map, UnionFind regions, List<ResolvedBorderRequirement> requirementOfBiome) {
        int biomeCount = map.biomes().size();
        int[] biomeIndices = map.biomeIndices();
        int size = map.size();
        Map<Long, Integer> contactsByRegionAndBiome = new HashMap<>();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int sample = j * size + i;
                for (int[] step : FORWARD_NEIGHBORS) {
                    int neighbor = map.indexOf(i + step[0], j + step[1]);
                    tallyOneContact(regions, requirementOfBiome, biomeIndices, biomeCount, sample, neighbor, contactsByRegionAndBiome);
                }
            }
        }
        return contactsByRegionAndBiome;
    }

    private static void tallyOneContact(
        UnionFind regions, List<ResolvedBorderRequirement> requirementOfBiome, int[] biomeIndices, int biomeCount, int sample, int neighbor, Map<Long, Integer> contactsByRegionAndBiome
    ) {
        int here = biomeIndices[sample];
        int there = biomeIndices[neighbor];
        if (here == there) {
            return;
        }
        if (requirementOfBiome.get(here) != null) {
            contactsByRegionAndBiome.merge((long) regions.find(sample) * biomeCount + there, 1, Integer::sum);
        }
        if (requirementOfBiome.get(there) != null) {
            contactsByRegionAndBiome.merge((long) regions.find(neighbor) * biomeCount + here, 1, Integer::sum);
        }
    }

    private static List<BorderShortfall> shortfallsOf(
        BiomeMap map, List<Integer> regionRoots, List<ResolvedBorderRequirement> requirementOfBiome, Map<Long, Integer> contactsByRegionAndBiome, double requiredBlocksPerEdge
    ) {
        int biomeCount = map.biomes().size();
        int[] biomeIndices = map.biomeIndices();
        List<BorderShortfall> shortfalls = new ArrayList<>();
        for (int root : regionRoots) {
            checkBorderRequirement(map, biomeIndices, requirementOfBiome, contactsByRegionAndBiome, biomeCount, requiredBlocksPerEdge, shortfalls, root);
        }
        return shortfalls;
    }

    private static void checkBorderRequirement(
        BiomeMap map, int[] biomeIndices, List<ResolvedBorderRequirement> requirementOfBiome, Map<Long, Integer> contactsByRegionAndBiome, int biomeCount,
        double requiredBlocksPerEdge, List<BorderShortfall> shortfalls, int root
    ) {
        ResolvedBorderRequirement requirement = requirementOfBiome.get(biomeIndices[root]);
        if (requirement == null) {
            return;
        }
        ToDoubleFunction<BiomeId> sharedBlocksWith = other -> contactsByRegionAndBiome.getOrDefault((long) root * biomeCount + map.biomes().indexOf(other), 0) * map.blocksPerSample() / 2;
        if (!requirement.isMetBy(sharedBlocksWith, requiredBlocksPerEdge)) {
            shortfalls.add(new BorderShortfall(map.biomes().get(biomeIndices[root]), requirement.toString()));
        }
    }

    // ---- pattern-cluster comparison ----

    private static Report withClusterComparison(
        BiomeMap map, RegionRoots regionRoots, UnionFind regions, PatternClusters clusters,
        List<AreaViolation> violations, Map<String, Integer> illegalContacts, List<BorderShortfall> shortfalls, int legalContacts
    ) {
        int sampleCount = map.size() * map.size();
        RegionClusters regionClusters = mapRegionsToClusters(map, regions, clusters, sampleCount);
        int[] regionsOfCluster = new int[clusters.clusterCount()];
        double[] drift = new double[regionRoots.roots().size()];
        int mergedComponents = fillDriftAndClusterCounts(map, regionRoots, clusters, regionClusters, regionsOfCluster, drift);
        int vanished = countWhere(regionsOfCluster, count -> count == 0);
        int split = countWhere(regionsOfCluster, count -> count > 1);
        return new Report(regionRoots.roots().size(), violations, illegalContacts, shortfalls, legalContacts, drift, vanished, split, mergedComponents);
    }

    /** Which pattern cluster each region's samples were rasterized from, and whether a region ever disagreed with itself (samples from more than one cluster - a merge). */
    private static RegionClusters mapRegionsToClusters(BiomeMap map, UnionFind regions, PatternClusters clusters, int sampleCount) {
        int[] clusterOfRegion = new int[sampleCount];
        Arrays.fill(clusterOfRegion, -1);
        boolean[] regionIsMerged = new boolean[sampleCount];
        int[] cells = map.cells();
        for (int sample = 0; sample < sampleCount; sample++) {
            assignClusterToRegion(regions, clusters, cells, clusterOfRegion, regionIsMerged, sample);
        }
        return new RegionClusters(clusterOfRegion, regionIsMerged);
    }

    private static void assignClusterToRegion(UnionFind regions, PatternClusters clusters, int[] cells, int[] clusterOfRegion, boolean[] regionIsMerged, int sample) {
        int root = regions.find(sample);
        int cluster = clusters.clusterOfCell()[cells[sample]];
        if (clusterOfRegion[root] == -1) {
            clusterOfRegion[root] = cluster;
        } else if (clusterOfRegion[root] != cluster) {
            regionIsMerged[root] = true;
        }
    }

    /** Fills {@code regionsOfCluster} (how many regions each pattern cluster rasterized to) and {@code drift} (one entry per region root, same order as {@code regionRoots.roots()}); returns how many regions are a merge of more than one cluster. */
    private static int fillDriftAndClusterCounts(BiomeMap map, RegionRoots regionRoots, PatternClusters clusters, RegionClusters regionClusters, int[] regionsOfCluster, double[] drift) {
        int sampleCount = map.size() * map.size();
        double tileArea = map.sampleAreaBlocks2() * sampleCount;
        int mergedComponents = 0;
        for (int index = 0; index < regionRoots.roots().size(); index++) {
            if (accumulateRegionDrift(map, regionRoots, clusters, regionClusters, regionsOfCluster, drift, tileArea, index)) {
                mergedComponents++;
            }
        }
        return mergedComponents;
    }

    /** Updates {@code regionsOfCluster} and {@code drift} for region {@code index}; returns whether that region is a merge of more than one cluster. */
    private static boolean accumulateRegionDrift(
        BiomeMap map, RegionRoots regionRoots, PatternClusters clusters, RegionClusters regionClusters, int[] regionsOfCluster, double[] drift, double tileArea, int index
    ) {
        int root = regionRoots.roots().get(index);
        int cluster = regionClusters.clusterOfRegion()[root];
        regionsOfCluster[cluster]++;
        double expectedArea = clusters.cellsInCluster()[cluster] * tileArea / clusters.totalCells();
        drift[index] = regionRoots.samplesInRegion()[root] * map.sampleAreaBlocks2() / expectedArea - 1;
        return regionClusters.regionIsMerged()[root];
    }

    private static int countWhere(int[] values, IntPredicate predicate) {
        int count = 0;
        for (int value : values) {
            count += accumulateMatchingValue(predicate, value);
        }
        return count;
    }

    private static int accumulateMatchingValue(IntPredicate predicate, int value) {
        return predicate.test(value) ? 1 : 0;
    }

    // ---- shared lookups ----

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
                tallyIllegalContactPair(map, illegalContactCounts, biomeCount, named, low, high);
            }
        }
        return named;
    }

    private static void tallyIllegalContactPair(BiomeMap map, int[] illegalContactCounts, int biomeCount, Map<String, Integer> named, int low, int high) {
        int count = illegalContactCounts[low * biomeCount + high];
        if (count > 0) {
            BiomeId first = map.biomes().get(low);
            BiomeId second = map.biomes().get(high);
            boolean firstIsLower = first.compareTo(second) <= 0;
            named.merge((firstIsLower ? first : second) + " | " + (firstIsLower ? second : first), count, Integer::sum);
        }
    }
}
