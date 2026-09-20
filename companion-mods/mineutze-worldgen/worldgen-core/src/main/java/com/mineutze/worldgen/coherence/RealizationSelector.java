package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Chooses how hard to bend a world's borders (2026-09-20). Stronger warps look more natural but
 * stretch shapes into filaments that the map can no longer hold: at some strength a thin tendril
 * breaks apart, a cluster splits and a size limit is broken. Where that happens depends on the
 * pattern, so the strength cannot be fixed in advance. Given candidate strengths, strongest first,
 * this rasterizes the world with each in turn, verifies the map directly ({@link BiomeMapVerifier})
 * and returns the FIRST that passes: every cluster still exactly one region, no region outside its
 * size range (with the tolerance), no biome touching one it may not. If none passes it fails with
 * every attempt's reason - a world is never given a map that was not checked.
 */
final class RealizationSelector {

    static final int WAVE_COUNT = 40;
    static final double SHORTEST_WAVELENGTH_BLOCKS = 40;
    static final double LONGEST_WAVELENGTH_BLOCKS = 1_200;
    static final double ROUGHNESS = 0.8;
    static final int FLOW_STEPS = 8;

    private static final long WORLD_SEED_SALT = 0x51AF_2D07_9C3B_E4A1L;

    private RealizationSelector() {
    }

    /** The winning strength, its warp, the verified map itself, the report that cleared it and how many strengths were tried to get there. */
    record Selection(double strength, DivergenceFreeWarp warp, BiomeMap map, BiomeMapVerifier.Report report, int candidatesTried, List<BiomeId> biomeOrder) {
    }

    static Selection select(
        RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> pattern, HexTorusGeometry geometry, long worldSeed, double[] strengthsStrongestFirst,
        int samplesPerSide, double toleranceOfMinimum, List<BiomeId> biomeOrder
    ) {
        if (strengthsStrongestFirst.length == 0) {
            throw new IllegalArgumentException("RealizationSelector: needs at least one candidate strength");
        }
        PatternClusters clusters = PatternClusters.of(grid, pattern);
        long warpSeed = new SplittableRandom(worldSeed ^ WORLD_SEED_SALT).nextLong();
        StringBuilder attempts = new StringBuilder();
        for (int index = 0; index < strengthsStrongestFirst.length; index++) {
            double strength = strengthsStrongestFirst[index];
            DivergenceFreeWarp warp = warpOfStrength(geometry, warpSeed, strength);
            BiomeMap map = BiomeMapRasterizer.rasterize(grid, pattern, geometry, warp, samplesPerSide, biomeOrder);
            BiomeMapVerifier.Report report = BiomeMapVerifier.verify(map, table, toleranceOfMinimum, clusters);
            if (passes(report)) {
                return new Selection(strength, warp, map, report, index + 1, biomeOrder);
            }
            attempts.append("\n  strength ").append(describe(strength)).append(": ").append(whyItFailed(report));
        }
        throw new IllegalStateException(
            "RealizationSelector: no strength produced a valid map for world seed " + worldSeed + " on a " + grid.width() + "x" + grid.height() + " torus ("
                + samplesPerSide + " samples per side, tolerance " + toleranceOfMinimum + " of the minimum). Attempts:" + attempts
        );
    }

    private static DivergenceFreeWarp warpOfStrength(HexTorusGeometry geometry, long warpSeed, double strength) {
        if (strength == 0) {
            return DivergenceFreeWarp.none(geometry);
        }
        return new DivergenceFreeWarp(
            geometry, warpSeed, new DivergenceFreeWarp.Settings(WAVE_COUNT, SHORTEST_WAVELENGTH_BLOCKS, LONGEST_WAVELENGTH_BLOCKS, strength, ROUGHNESS, FLOW_STEPS)
        );
    }

    /** A map is acceptable only if every check is clean: no cluster vanished, split or merged, no region outside its size range, no illegal contact. */
    static boolean passes(BiomeMapVerifier.Report report) {
        return report.vanishedClusters() == 0 && report.splitClusters() == 0 && report.mergedComponents() == 0
            && report.areaViolations().isEmpty() && report.illegalContactSamples().isEmpty();
    }

    private static String whyItFailed(BiomeMapVerifier.Report report) {
        return "vanished " + report.vanishedClusters() + ", split " + report.splitClusters() + ", merged " + report.mergedComponents() + ", area violations "
            + report.areaViolations().size() + ", illegal contact pairs " + report.illegalContactSamples().size();
    }

    private static String describe(double strength) {
        return strength == Math.rint(strength) ? String.valueOf((long) strength) : String.valueOf(strength);
    }
}
