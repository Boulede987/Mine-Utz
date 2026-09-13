package com.mineutze.worldgen.regionsolve.topology.identity;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-biome target share of a region's free triangle faces, and each biome's running
 * deficit against that target during {@link TriangleIdentitySizeAssignment}'s CSP
 * backtracking — legality-only random candidate picking let {@code BiomeGraph} topology
 * (a biome's degree/how "central" it is) drive systematic rich-get-richer triangle-count
 * skew, independent of graph/region size (see {@code
 * ModBiomeGraphTest.noBiomeIsStarvedOnARealProductionScaleSolve}: real, re-runnable proof
 * {@code ModBiomeGraph.DEFAULT} doesn't suffer this today). {@link
 * TriangleIdentitySizeAssignment#candidatesInFairnessOrder} consults {@link #deficit} so
 * an under-represented biome is preferred when two candidates are otherwise equally legal
 * — never overrides legality/backtracking, only reorders already-tied choices.
 */
final class FaceFairnessQuota {

    // Extra same-biome patches (beyond one) a region can hand one biome, on top of its fair
    // AREA share. See maxTrianglesPerBiome for derivation - generous (not 1) since multiple
    // disconnected patches of the same biome per region is expected, not a bug.
    private static final double PATCH_COUNT_SLACK = 4.0;

    private final Map<BiomeId, Double> quotas;
    private final Map<BiomeId, Integer> counts = new HashMap<>();
    private final int capPerBiome;

    private FaceFairnessQuota(Map<BiomeId, Double> quotas, int capPerBiome) {
        this.quotas = quotas;
        this.capPerBiome = capPerBiome;
    }

    static FaceFairnessQuota forRegion(List<BiomeId> allBiomes, int totalFreeFaces, double regionAreaBlocks2) {
        Map<BiomeId, Double> weights = fairShareWeights(allBiomes);
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();

        Map<BiomeId, Double> quotas = new HashMap<>();
        weights.forEach((biome, weight) -> quotas.put(biome, totalWeight <= 0 ? 0.0 : weight / totalWeight * totalFreeFaces));

        return new FaceFairnessQuota(quotas, maxTrianglesPerBiome(regionAreaBlocks2, weights));
    }

    /**
     * Every biome gets SAME weight - "mostly equally rare" is the goal, not fair-by-area.
     * Used to scale a per-{@code BiomeSizeRange} midpoint SQUARED back when a biome's
     * territory was assembled by {@code SeamGrowthRasterizer} competitively growing many
     * mesh cells into one patch - wide-range biome needed more raw material then, so
     * weighting by size² made sense. Doesn't hold anymore: under hex + edge-displacement
     * (see doc/decisions/2026-08-29-hex-mesh-edge-displacement.md), especially once
     * self-adjacency removed, every face already IS one full-sized patch regardless of
     * biome - size² weighting measured to hand the ~11 widest-range biomes (ocean/mountain
     * cluster) several times a narrow-range one's priority, starving ~31 biomes across
     * every seed tried, independent of legality (all had real legal placement, just never
     * won the ordering tie-break before mesh filled up).
     *
     * <p>Deliberately NOT weighted by placement difficulty (e.g. graph degree) either - no
     * measured evidence uniform weighting leaves narrow-degree biomes behind; add that
     * correction only if a real stress-test (mirroring {@code ModBiomeGraphTest}'s
     * starvation tests) shows it's needed.
     */
    private static Map<BiomeId, Double> fairShareWeights(List<BiomeId> allBiomes) {
        Map<BiomeId, Double> weights = new LinkedHashMap<>();
        for (BiomeId biome : allBiomes) {
            weights.put(biome, 1.0);
        }
        return weights;
    }

    /**
     * How many triangles ANY ONE biome should get in this region, as an UPPER BOUND — fixes
     * a real, measured mismatch: {@link #fairShareWeights}'s quota is proportional to TOTAL
     * FREE FACES (MESH DENSITY, driven by {@code SeedGrid} point spacing), no relationship
     * to how many real, sensibly-sized patches a biome could physically fit in a region this
     * size. On a real run this handed one narrow-range biome (badlands, 500-800 block
     * target) 20 identity triangles in a single 2048-block region - a region that can hold
     * only a handful of 500-800-wide patches TOTAL across every biome. All 20 fought each
     * other and bigger neighbors for the same sliver of space, none won enough - direct
     * cause of a real crash in the geometry-realization rasterizer (that biome collapsing to
     * isolated 1-2-cell islands, chaotic high-arity neighborhoods).
     *
     * <p>Derivation, updated for uniform {@link #fairShareWeights}: weight 1.0 everywhere
     * means {@code totalWeight} is just biome count, so {@code regionAreaBlocks2 /
     * totalWeight} is region area divided evenly across every biome - flat per-biome area
     * budget, independent of size range (used to cancel a size-based weight; now uniform,
     * nothing left to cancel, just an even split). {@link #PATCH_COUNT_SLACK} multiplies
     * that up to allow several disconnected patches, not just one - normal, expected.
     *
     * <p>SOFT cap only — see {@link #deficit}: steers CSP candidate ordering away from an
     * already-capped biome, never removes it from a face's legal domain, so never makes an
     * otherwise-solvable mesh unsolvable.
     */
    private static int maxTrianglesPerBiome(double regionAreaBlocks2, Map<BiomeId, Double> weights) {
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight <= 0 || Double.isInfinite(regionAreaBlocks2)) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(PATCH_COUNT_SLACK * regionAreaBlocks2 / totalWeight));
    }

    /** Never overrides legality or backtracking (see class doc) - a capped biome sorts last among still-legal candidates, so it can still be picked if it's the only legal option left. */
    double deficit(BiomeId biome) {
        if (counts.getOrDefault(biome, 0) >= capPerBiome) {
            return Double.NEGATIVE_INFINITY;
        }
        return quotas.getOrDefault(biome, 0.0) - counts.getOrDefault(biome, 0);
    }

    void increment(BiomeId biome) {
        counts.merge(biome, 1, Integer::sum);
    }

    void decrement(BiomeId biome) {
        counts.merge(biome, -1, Integer::sum);
    }
}
