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

    // Placeholder guess, NOT calibrated -- vanilla rivers occupy a narrow band right at
    // weirdness's "valley" extremum (its own ridges-folding function, not raw weirdness
    // directly, which this approximates), independent of continentalness -- a river can
    // cut through land that's otherwise flat, hilly, or mountainous. Without this check,
    // land biomes had no way to avoid growing straight across small inland water bodies
    // (rivers/lakes) that share ordinary FLAT_LAND continentalness. Needs the same real
    // calibration as the other thresholds below before this is more than a guess at the
    // right band width.
    private static final float RIVER_WEIRDNESS_ABS_HI = 0.05f;

    // Placeholder guesses -- NOT calibrated against this modpack's real noise router
    // yet. BoP/BWG may shift these breakpoints from vanilla's own. Do not trust
    // visually until calibrate_terrain_classes.py has run against a real
    // /dump_height_samples capture (DESIGN.md Sec 11 / rollout Milestone 3b).
    private static final float MOUNTAINOUS_EROSION_HI       = -0.4f;
    private static final float HILLY_EROSION_HI             =  0.2f;
    private static final float MOUNTAINOUS_WEIRDNESS_ABS_LO =  0.6f;

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
