package com.mineutze.worldgen.regionsolve.topology;

import com.mineutze.worldgen.biomegraph.BiomeId;

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

    static FaceFairnessQuota forRegion(List<BiomeId> allBiomes, int totalFreeFaces, double regionAreaBlocks2) {
        Map<BiomeId, Double> weights = fairShareWeights(allBiomes);
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();

        Map<BiomeId, Double> quotas = new HashMap<>();
        weights.forEach((biome, weight) -> quotas.put(biome, totalWeight <= 0 ? 0.0 : weight / totalWeight * totalFreeFaces));

        return new FaceFairnessQuota(quotas, maxTrianglesPerBiome(regionAreaBlocks2, weights));
    }

    /**
     * Every biome gets the SAME weight - "mostly equally rare" is the goal, not fair-by-
     * area. Used to scale a per-{@code BiomeSizeRange} midpoint SQUARED here, back when a
     * biome's territory was assembled by {@code SeamGrowthRasterizer} competitively
     * growing many mesh cells into one patch - a wide-range biome genuinely needed more
     * raw material to reach its footprint then, so weighting quota by size² made sense.
     * That assumption doesn't hold anymore: under the hex + edge-displacement pipeline
     * (see doc/decisions/2026-08-29-hex-mesh-edge-displacement.md), especially once
     * same-biome self-adjacency is removed, every face already IS one full-sized patch
     * regardless of which biome lands there - size² weighting was measured directly to
     * still hand the ~11 widest-range biomes (mostly the ocean/mountain cluster) several
     * times the initial priority of a narrow-range one, starving roughly the same ~31
     * biomes across every seed tried, independent of legality (every one of them had a
     * real, legal opportunity to place somewhere - they just never won the ordering
     * tie-break before the mesh filled up).
     *
     * <p>Deliberately NOT weighted by how structurally hard a biome is to place (e.g. its
     * own graph degree) either, despite that being a real, reasonable idea - no measured
     * evidence yet that uniform weighting alone leaves narrow-degree biomes behind; add
     * that correction only if a real stress-test run (mirroring {@code
     * ModBiomeGraphTest}'s own starvation tests) actually shows it's still needed.
     */
    private static Map<BiomeId, Double> fairShareWeights(List<BiomeId> allBiomes) {
        Map<BiomeId, Double> weights = new LinkedHashMap<>();
        for (BiomeId biome : allBiomes) {
            weights.put(biome, 1.0);
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
     * <p>Derivation, updated for uniform {@link #fairShareWeights}: with every weight equal
     * to 1.0, {@code totalWeight} is just the biome count, so {@code regionAreaBlocks2 /
     * totalWeight} is simply the region's area divided evenly across every biome — a flat
     * per-biome area budget, independent of that biome's own size range (this used to
     * "cancel out" a variable per-biome footprint weight when weight was size-based; with
     * weight now uniform, there's nothing left to cancel, it's just an even split). {@link
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
