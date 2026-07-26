package com.mineutze.worldgen.biomesource;

import net.minecraft.world.level.biome.Climate;

/**
 * Buckets a climate sample into a {@link TerrainClass}. Pure function of
 * (continentalness, erosion, weirdness), no height lookup needed -- depth is
 * deliberately excluded here, it's what routes surface vs. underground tier in
 * CustomOverworldBiomeSource, not a terrain-class input (see DESIGN.md Sec 4).
 *
 * Thresholds are calibrated via pythonScripts/worldgen/calibrate_terrain_classes.py
 * against a real /dump_height_samples capture (see that script's docstring) -- there is
 * no longer a Python mirror of this classifier itself to keep in lockstep with, that was
 * dropped once the growth algorithm's Python port was (see DESIGN.md).
 */
public final class TerrainClassifier {

    // Matches the old biome_grid.py's OCEAN_CONTINENTALNESS_HI / LAND_CONTINENTALNESS_LO
    // boundaries, already calibrated for this modpack's land/ocean split.
    private static final float OCEAN_CONTINENTALNESS_HI = -0.19f;
    private static final float COAST_CONTINENTALNESS_HI  =  0.03f;

    // Calibrated via ground truth, NOT the depression-proxy approach -- that kept failing
    // its own sanity check (peak depression should sit near weirdness=0 if the river band
    // is really centered there) on every real calibrate_terrain_classes.py run, including
    // a 117836-sample pool with a session deliberately following real rivers. Root cause:
    // Tectonic redirects weirdness/ridges through its own custom noise
    // (tectonic:biome_parameter/ridges, see TERRAIN_COHERENCE_DESIGN.md Sec 5) -- vanilla's
    // "narrow weirdness band = river" convention the depression proxy assumes doesn't hold
    // for Tectonic's real river mechanism. Real value instead comes from
    // /dump_river_samples (bulk-scans a grid, logs only columns whose real surface block
    // is confirmed water -- ground truth, not inference) fed into
    // calibrate_terrain_classes.py's percentile-based suggestion. See git history for the
    // exact run this value came from.
    private static final float RIVER_WEIRDNESS_ABS_HI = 0.066f;

    // Calibrated via calibrate_terrain_classes.py against 117836 pooled real
    // /dump_height_samples captures (two sessions, --world persistence) -- see git history
    // for the exact run. Stable across both sessions (unchanged when the sample count
    // roughly doubled between them), unlike RIVER_WEIRDNESS_ABS_HI above.
    private static final float MOUNTAINOUS_EROSION_HI       = 0.040f;
    private static final float HILLY_EROSION_HI             = 0.170f;
    private static final float MOUNTAINOUS_WEIRDNESS_ABS_LO = 0.216f;

    private TerrainClassifier() {
    }

    public static TerrainClass classify(double continentalness, double erosion, double weirdness) {
        if (continentalness < OCEAN_CONTINENTALNESS_HI) {
            return TerrainClass.OCEAN;
        }
        if (continentalness < COAST_CONTINENTALNESS_HI) {
            return TerrainClass.COAST;
        }
        if (Math.abs(weirdness) <= RIVER_WEIRDNESS_ABS_HI) {
            return TerrainClass.RIVER;
        }
        if (erosion <= MOUNTAINOUS_EROSION_HI && Math.abs(weirdness) >= MOUNTAINOUS_WEIRDNESS_ABS_LO) {
            return TerrainClass.MOUNTAINOUS;
        }
        if (erosion <= HILLY_EROSION_HI) {
            return TerrainClass.HILLY;
        }
        return TerrainClass.FLAT_LAND;
    }

    /** Samples the real climate signal at one exact block position and classifies it --
     * shared by SurfacePatchGrid (per-cell and per-block warp-validation reads) and
     * CustomOverworldBiomeSource's gap fallback, so there's one place that does the
     * sample-then-unquantize-then-classify sequence, not several copies of it. */
    public static TerrainClass classifyAtBlock(Climate.Sampler sampler, int blockX, int blockZ) {
        Climate.TargetPoint sample = sampler.sample(blockX >> 2, 0, blockZ >> 2);
        return classify(
            Climate.unquantizeCoord(sample.continentalness()),
            Climate.unquantizeCoord(sample.erosion()),
            Climate.unquantizeCoord(sample.weirdness())
        );
    }
}
