package com.mineutze.worldgen.biomesource;

/**
 * One entry in a biome's weighted set of desired {@link RegionFamily} shapes -- rolled
 * once per patch at germination (see PatchGrowthEngine.pickWeightedRegionFamily), the
 * same weighted-pick pattern BiomeConfig itself uses at the biome level, one level down.
 * A weighted SET rather than a single fixed family avoids every patch of a biome
 * rendering the same Tectonic shape template (see TERRAIN_COHERENCE_DESIGN.md Sec 4c).
 */
public record RegionFamilyWeight(RegionFamily family, float weight) {
}
