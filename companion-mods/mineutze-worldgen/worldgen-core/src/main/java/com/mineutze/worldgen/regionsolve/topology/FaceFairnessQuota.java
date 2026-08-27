package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeGraph;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BiomeSizeRange;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-biome target share of a region's free triangle faces, and each biome's running
 * deficit against that target during {@link TriangleIdentitySizeAssignment}'s own CSP
 * backtracking — legality-only random candidate picking let {@code BiomeGraph} topology
 * (a biome's own degree/how "central" it is in the adjacency graph) drive systematic
 * rich-get-richer triangle-count skew, independent of graph size or region scale (see
 * {@code ModBiomeGraphTest.noBiomeIsStarvedOnARealProductionScaleSolve} for the real,
 * re-runnable proof that {@code ModBiomeGraph.DEFAULT} doesn't suffer this today — that
 * test runs the real CSP against a real mesh and asserts every biome gets real
 * representation, rather than relying on a memory of some past experiment). {@link
 * TriangleIdentitySizeAssignment#candidatesInFairnessOrder} consults {@link #deficit}
 * so an under-represented biome is preferred whenever two candidates are otherwise
 * equally legal — this never overrides legality or backtracking itself, only reorders
 * choices that are already tied.
 */
final class FaceFairnessQuota {

    // How many extra same-biome patches (beyond one) a region is allowed to hand a single
    // biome, on top of that biome's own fair AREA share. See maxTrianglesPerBiome's own doc
    // for the full derivation - kept generous (not 1) because "multiple disconnected
    // patches of the same biome in a region" is expected, real behavior, not a bug.
    private static final double PATCH_COUNT_SLACK = 4.0;

    private final Map<BiomeId, Double> quotas;
    private final Map<BiomeId, Integer> counts = new HashMap<>();
    private final int capPerBiome;

    private FaceFairnessQuota(Map<BiomeId, Double> quotas, int capPerBiome) {
        this.quotas = quotas;
        this.capPerBiome = capPerBiome;
    }

    static FaceFairnessQuota forRegion(List<BiomeId> allBiomes, BiomeGraph graph, int totalFreeFaces, double regionAreaBlocks2) {
        Map<BiomeId, Double> weights = fairShareWeights(allBiomes, graph);
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();

        Map<BiomeId, Double> quotas = new HashMap<>();
        weights.forEach((biome, weight) -> quotas.put(biome, totalWeight <= 0 ? 0.0 : weight / totalWeight * totalFreeFaces));

        return new FaceFairnessQuota(quotas, maxTrianglesPerBiome(regionAreaBlocks2, weights));
    }

    /** Each biome's target share of total free faces, proportional to its {@link BiomeSizeRange} midpoint SQUARED — final territory is an area, so fair share must scale with area, not width. */
    private static Map<BiomeId, Double> fairShareWeights(List<BiomeId> allBiomes, BiomeGraph graph) {
        Map<BiomeId, Double> weights = new LinkedHashMap<>();
        for (BiomeId biome : allBiomes) {
            BiomeSizeRange range = graph.node(biome).sizeRange();
            double midWidth = (range.minWidthBlocks() + range.maxWidthBlocks()) / 2.0;
            weights.put(biome, midWidth * midWidth);
        }
        return weights;
    }

    /**
     * How many triangles ANY ONE biome should be handed in this region, as an UPPER BOUND
     * — a real, measured architecture mismatch this fixes: {@link #fairShareWeights}'s own
     * quota is proportional to TOTAL FREE FACES (a MESH DENSITY number, driven by how
     * closely {@code SeedGrid} places points), which has no relationship to how many real,
     * separate, sensibly-sized patches a biome could ever physically fit in a region this
     * size. On a real production run this handed one narrow-range biome (badlands, 500-800
     * block target) 20 separate identity triangles in a single 2048-block region — a region
     * that can geometrically hold only a handful of 500-800-wide patches TOTAL, across every
     * biome combined. All 20 of that biome's triangles then fought each other (as well as
     * bigger neighbors) for the same sliver of space, and none of them reliably won enough
     * of it — the direct cause of a real crash in the geometry-realization rasterizer of
     * the time (that biome collapsing to isolated 1-2-cell islands with chaotic,
     * high-arity neighborhoods).
     *
     * <p>Derivation: a biome's fair AREA share of the region is {@code regionAreaBlocks2 *
     * weight / totalWeight}. Dividing that by the biome's OWN average patch footprint
     * ({@code weight}, since {@link #fairShareWeights} already defines weight as
     * midpoint-width SQUARED) gives "how many of my own average patches fit in my own fair
     * share" — and the {@code weight} in the numerator and denominator cancels, so this
     * comes out to the SAME number for every biome: {@code regionAreaBlocks2 / totalWeight}.
     * That's not a coincidence or a bug - fairness already scales each biome's area quota by
     * its own footprint, so "how many of my own patches fit in my own share" is naturally
     * uniform across biomes once you're fair by area in the first place. {@link
     * #PATCH_COUNT_SLACK} multiplies that up to allow a biome several real disconnected
     * patches (not just one), matching this codebase's own established expectation that
     * multiple patches of the same biome per region is normal.
     *
     * <p>A SOFT cap only — see {@link #deficit}: it steers the CSP's own candidate
     * ordering away from an already-capped biome, but never removes it from a face's legal
     * domain, so this can never make an otherwise-solvable mesh unsolvable.
     */
    private static int maxTrianglesPerBiome(double regionAreaBlocks2, Map<BiomeId, Double> weights) {
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalWeight <= 0 || Double.isInfinite(regionAreaBlocks2)) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(PATCH_COUNT_SLACK * regionAreaBlocks2 / totalWeight));
    }

    /** Never overrides legality or backtracking (see this class's own doc) - a capped biome just sorts last among still-legal candidates, so it can still be picked if it's genuinely the only legal option left. */
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
