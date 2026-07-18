package com.mineutze.worldgen.biomesource;

/**
 * Surface terrain buckets a biome can declare eligibility for. See
 * {@link TerrainClassifier} -- must stay in lockstep with
 * pythonScripts/worldgen/terrain_classifier.py's TerrainClass enum.
 */
public enum TerrainClass {
    OCEAN,
    COAST,
    FLAT_LAND,
    HILLY,
    MOUNTAINOUS
}
