"""
worldgen/terrain_classifier.py
================================
Buckets a climate sample into a terrain class -- ocean/coast/flat_land/hilly/mountainous.
Pure function of (continentalness, erosion, weirdness), no height lookup needed: mirrors
the Java TerrainClassifier reading straight off Climate.Sampler output (see DESIGN.md
Sec 4 -- depth is deliberately excluded, it's what routes surface vs. underground tier,
not a terrain-class input).

Patches (patch_engine.py) only grow into cells whose terrain class is in the growing
biome's configured eligible set -- this is what keeps a mountain biome off flat ground,
not a post-hoc filter.
"""

from enum import Enum


class TerrainClass(Enum):
    OCEAN       = 'ocean'
    COAST       = 'coast'
    FLAT_LAND   = 'flat_land'
    HILLY       = 'hilly'
    MOUNTAINOUS = 'mountainous'


# Matches biome_grid.py's OCEAN_CONTINENTALNESS_HI / LAND_CONTINENTALNESS_LO boundaries,
# already calibrated for this modpack's land/ocean split.
OCEAN_CONTINENTALNESS_HI = -0.19
COAST_CONTINENTALNESS_HI =  0.03

# Placeholder guesses -- NOT calibrated against this modpack's real noise router yet.
# BoP/BWG may shift these breakpoints from vanilla's own. Do not trust visually until
# calibrate_terrain_classes.py has run against a real /dump_height_samples capture
# (DESIGN.md Sec 11 / rollout Milestone 3b).
MOUNTAINOUS_EROSION_HI       = -0.4
HILLY_EROSION_HI             =  0.2
MOUNTAINOUS_WEIRDNESS_ABS_LO =  0.6


def classify_terrain(continentalness: float, erosion: float, weirdness: float) -> TerrainClass:
    if continentalness < OCEAN_CONTINENTALNESS_HI:
        return TerrainClass.OCEAN
    if continentalness < COAST_CONTINENTALNESS_HI:
        return TerrainClass.COAST
    if erosion <= MOUNTAINOUS_EROSION_HI and abs(weirdness) >= MOUNTAINOUS_WEIRDNESS_ABS_LO:
        return TerrainClass.MOUNTAINOUS
    if erosion <= HILLY_EROSION_HI:
        return TerrainClass.HILLY
    return TerrainClass.FLAT_LAND
