from .climate import mc, bwg
from .arrays import (
    OCEANS_BWG, OCEANS_VANILLA,
    MIDDLE_1, MIDDLE_2, MIDDLE_3,
    MIDDLE_VARIANT_BWG, MIDDLE_VARIANT_VANILLA,
    PLATEAU_1, PLATEAU_2, PLATEAU_3,
    PLATEAU_VARIANT_BWG, PLATEAU_VARIANT_VANILLA,
    PEAK_BWG, PEAK_VARIANT_BWG, PEAK_VANILLA,
    SLOPE_BWG, SLOPE_VARIANT_VANILLA,
    SHATTERED_BWG, SHATTERED_VANILLA,
    BEACH_BWG, BEACH_VANILLA_TERRABLENDER,
)
from .builder import BWGBuilder

_SLOPE_VANILLA_ALL = [[None]*5]*5

REGION_1 = BWGBuilder(
    label='region_1',
    oceans=OCEANS_BWG,       middle=MIDDLE_1,  middle_variant=MIDDLE_VARIANT_BWG,
    plateau=PLATEAU_1,        plateau_variant=PLATEAU_VARIANT_BWG,
    shattered=SHATTERED_BWG,  beach=BEACH_BWG,
    peak=PEAK_BWG,            peak_variant=PEAK_VARIANT_BWG,
    slope=SLOPE_BWG,          slope_variant=SLOPE_VARIANT_VANILLA,
    swapper={mc('mangrove_swamp'): bwg('cypress_swamplands'), mc('swamp'): bwg('bayou')},
)
REGION_2 = BWGBuilder(
    label='region_2',
    oceans=OCEANS_BWG,        middle=MIDDLE_2,  middle_variant=MIDDLE_VARIANT_VANILLA,
    plateau=PLATEAU_2,         plateau_variant=PLATEAU_VARIANT_VANILLA,
    shattered=SHATTERED_VANILLA, beach=BEACH_VANILLA_TERRABLENDER,
    peak=PEAK_VANILLA,         peak_variant=PEAK_VANILLA,
    slope=_SLOPE_VANILLA_ALL,  slope_variant=SLOPE_VARIANT_VANILLA,
    swapper={mc('mangrove_swamp'): bwg('white_mangrove_marshes'), mc('swamp'): bwg('cypress_wetlands')},
)
REGION_3 = BWGBuilder(
    label='region_3',
    oceans=OCEANS_VANILLA,    middle=MIDDLE_3,  middle_variant=MIDDLE_VARIANT_VANILLA,
    plateau=PLATEAU_3,         plateau_variant=PLATEAU_VARIANT_VANILLA,
    shattered=SHATTERED_VANILLA, beach=BEACH_VANILLA_TERRABLENDER,
    peak=PEAK_VANILLA,         peak_variant=PEAK_VANILLA,
    slope=_SLOPE_VANILLA_ALL,  slope_variant=SLOPE_VARIANT_VANILLA,
    swapper={mc('mangrove_swamp'): bwg('pale_bog'), mc('swamp'): bwg('cypress_swamplands')},
)

ALL_REGIONS = [REGION_1, REGION_2, REGION_3]
