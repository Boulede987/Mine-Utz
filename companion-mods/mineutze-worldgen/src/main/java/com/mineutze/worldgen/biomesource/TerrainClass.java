package com.mineutze.worldgen.biomesource;

/**
 * Surface terrain buckets a biome can declare eligibility for. See
 * {@link TerrainClassifier}.
 */
public enum TerrainClass {
    OCEAN,
    COAST,
    FLAT_LAND,
    HILLY,
    MOUNTAINOUS,
    // Inland water (rivers/lakes) -- a narrow band near weirdness's "valley" point,
    // independent of the macro continentalness signal that drives OCEAN/COAST, so a
    // river cell can sit deep inside otherwise-FLAT_LAND continentalness. See
    // TerrainClassifier's own comment for why this needed its own bucket: without it,
    // land biomes had no way to avoid growing straight across small inland water bodies.
    RIVER
}
