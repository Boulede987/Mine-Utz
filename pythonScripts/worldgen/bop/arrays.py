from .climate import mc, bop

# ── Primary ───────────────────────────────────────────────────────────────────

P_MIDDLE = [
    [bop('cold_desert'),       bop('cold_desert'),       bop('snowy_coniferous_forest'), bop('snowy_coniferous_forest'), bop('snowy_coniferous_forest')],
    [bop('coniferous_forest'), bop('coniferous_forest'), bop('coniferous_forest'),        bop('field'),                   bop('field')],
    [bop('pasture'),           bop('prairie'),            bop('redwood_forest'),           bop('overgrown_greens'),        bop('overgrown_greens')],
    [bop('scrubland'),         bop('scrubland'),          bop('woodland'),                 bop('rainforest'),              bop('rainforest')],
    [bop('dryland'),           bop('dryland'),            bop('lush_desert'),              bop('lush_desert'),             bop('lush_desert')],
]
P_MIDDLE_VARIANT = [
    [None, None, bop('snowy_fir_clearing'), bop('snowy_fir_clearing'), bop('snowy_maple_woods')],
    [None, bop('fir_clearing'), None, None, bop('forested_field')],
    [None, None, None, None, bop('grassland')],
    [None, None, bop('old_growth_woodland'), None, bop('rocky_rainforest')],
    [None, None, None, bop('lush_savanna'), bop('lush_savanna')],
]
P_PLATEAU = [
    [bop('cold_desert'),       bop('cold_desert'),       bop('snowy_fir_clearing'), bop('snowy_fir_clearing'), bop('snowy_coniferous_forest')],
    [bop('coniferous_forest'), bop('coniferous_forest'), bop('highland'),            bop('highland'),           bop('field')],
    [bop('prairie'),           bop('prairie'),            bop('highland'),            bop('highland'),           bop('highland')],
    [bop('scrubland'),         bop('scrubland'),          bop('woodland'),            bop('rainforest'),         bop('rocky_rainforest')],
    [bop('dryland'),           bop('dryland'),            bop('lush_desert'),         bop('lush_desert'),        bop('lush_desert')],
]
P_PLATEAU_VARIANT = [
    [None]*5,
    [None, None, bop('coniferous_forest'), bop('field'), None],
    [bop('pasture'), None, bop('redwood_forest'), bop('overgrown_greens'), bop('moor')],
    [None, None, None, bop('rocky_rainforest'), None],
    [None, None, None, None, bop('lush_savanna')],
]
P_SLOPE = [
    [None]*5,
    [bop('crag')]*5,
    [bop('jade_cliffs')]*5,
    [None, None, None, bop('volcano'), bop('volcano')],
    [None]*5,
]
P_PEAK = [
    [None]*5,
    [bop('crag')]*5,
    [bop('jade_cliffs')]*5,
    [None, None, None, bop('volcano'), bop('volcano')],
    [None]*5,
]
P_EXTREME_HILLS = [
    [None]*5,
    [bop('highland')]*5,
    [bop('highland')]*5,
    [None, None, None, bop('volcano'), bop('volcano')],
    [None]*5,
]
P_SWAMP = [
    [bop('hot_springs')]*5,
    [bop('wetland')]*5,
    [bop('marsh')]*5,
    [bop('bayou'), bop('bayou'), bop('bayou'), bop('floodplain'), bop('floodplain')],
    [bop('bayou'), bop('bayou'), bop('bayou'), bop('floodplain'), bop('floodplain')],
]
P_RIVER = [
    [mc('frozen_river')]*5,
    [None]*5, [None]*5,
    [None, None, None, bop('floodplain'), bop('floodplain')],
    [None]*5,
]
P_BEACH = [
    [mc('snowy_beach')]*5,
    [bop('gravel_beach')]*5,
    [bop('dune_beach'), bop('dune_beach'), bop('dune_beach'), mc('beach'), mc('beach')],
    [mc('beach'), mc('beach'), mc('beach'), bop('floodplain'), bop('floodplain')],
    [bop('dryland'), bop('dryland'), bop('lush_desert'), bop('lush_desert'), bop('lush_desert')],
]
P_STONY = [
    [None]*5, [None]*5, [None]*5,
    [None, None, None, bop('rocky_rainforest'), bop('rocky_rainforest')],
    [None, None, bop('lush_desert'), bop('lush_desert'), bop('lush_desert')],
]
P_ISLAND = [
    [bop('wintry_origin_valley')]*5,
    [bop('origin_valley')]*5,
    [None]*5,
    [bop('tropics')]*5,
    [bop('tropics')]*5,
]

# ── Rare ──────────────────────────────────────────────────────────────────────

R_RARE = [
    [None, None, None, bop('auroral_garden'), None],
    [None, None, bop('ominous_woods'), None, None],
    [None, None, None, bop('mystic_grove'), None],
    [None, None, None, None, bop('fungal_jungle')],
    [None]*5,
]
R_RARE_VARIANT = [[None]*5]*5

# ── Secondary ─────────────────────────────────────────────────────────────────

S_MIDDLE = [
    [bop('tundra'),               bop('tundra'),               bop('muskeg'),          bop('snowy_maple_woods'), bop('dead_forest')],
    [bop('seasonal_forest'),      bop('seasonal_forest'),       bop('seasonal_forest'), bop('maple_woods'),       bop('maple_woods')],
    [bop('lavender_field'),       bop('lavender_field'),        bop('orchard'),         bop('shrubland'),         bop('shrubland')],
    [bop('mediterranean_forest'), bop('mediterranean_forest'),  bop('woodland'),        bop('rainforest'),        bop('volcanic_plains')],
    [bop('wasteland')]*5,
]
S_MIDDLE_VARIANT = [
    [None, None, None, bop('snowblossom_grove'), bop('old_growth_dead_forest')],
    [None, bop('aspen_glade'), bop('pumpkin_patch'), None, None],
    [None, bop('jacaranda_glade'), None, bop('rocky_shrubland'), bop('grassland')],
    [None, None, bop('old_growth_woodland'), bop('volcanic_plains'), None],
    [None]*5,
]
S_PLATEAU = [
    [bop('tundra'),               bop('tundra'),               bop('snowblossom_grove'), bop('snowy_maple_woods'), bop('dead_forest')],
    [bop('seasonal_forest'),      bop('aspen_glade'),          bop('highland'),          bop('highland'),          bop('highland')],
    [bop('lavender_field'),       bop('lavender_field'),       bop('orchard'),           bop('rocky_shrubland'),   bop('highland')],
    [bop('mediterranean_forest'), bop('mediterranean_forest'), bop('woodland'),          bop('rocky_rainforest'),  bop('volcano')],
    [bop('wasteland_steppe')]*5,
]
S_PLATEAU_VARIANT = [
    [None, None, None, bop('snowblossom_grove'), bop('old_growth_dead_forest')],
    [None, None, None, bop('maple_woods'), None],
    [None, bop('jacaranda_glade'), bop('highland'), None, bop('moor')],
    [None, None, bop('old_growth_woodland'), bop('volcano'), None],
    [None]*5,
]
S_SWAMP = [
    [bop('hot_springs')]*5,
    [bop('bog')]*5,
    [bop('marsh')]*5,
    [bop('bayou'), bop('bayou'), bop('bayou'), bop('floodplain'), bop('floodplain')],
    [bop('wasteland'), bop('wasteland'), bop('bayou'), bop('floodplain'), bop('floodplain')],
]
S_RIVER = [
    [mc('frozen_river'), mc('frozen_river'), bop('muskeg'), mc('frozen_river'), mc('river')],
    [None]*5, [None]*5, [None]*5,
    [bop('wasteland')]*5,
]
S_BEACH = [
    [mc('snowy_beach'), mc('snowy_beach'), mc('snowy_beach'), mc('snowy_beach'), bop('gravel_beach')],
    [bop('gravel_beach')]*5,
    [bop('dune_beach'), bop('dune_beach'), bop('dune_beach'), mc('beach'), mc('beach')],
    [bop('mediterranean_forest'), bop('mediterranean_forest'), mc('beach'), bop('floodplain'), bop('volcanic_plains')],
    [bop('wasteland')]*5,
]
S_STONY = [
    [None]*5, [None]*5, [None]*5,
    [None, None, None, None, bop('volcanic_plains')],
    [bop('wasteland')]*5,
]
