def span(a, b): return [a[0], b[1]]
def point(v):   return [v, v]

def mc(name):  return f'minecraft:{name}'
def bop(name): return f'biomesoplenty:{name}'

TEMPERATURES = [[-1.0, -0.45], [-0.45, -0.15], [-0.15, 0.2], [0.2, 0.55], [0.55, 1.0]]
HUMIDITIES   = [[-1.0, -0.35], [-0.35, -0.1], [-0.1, 0.1], [0.1, 0.3], [0.3, 1.0]]
EROSIONS     = [[-1.0, -0.78], [-0.78, -0.375], [-0.375, -0.2225],
                [-0.2225, 0.05], [0.05, 0.45], [0.45, 0.55], [0.55, 1.0]]

FULL             = [-1.0, 1.0]
CONT_MUSHROOM    = [-1.2, -1.05]
CONT_DEEP_OCEAN  = [-1.05, -0.455]
CONT_OCEAN       = [-0.455, -0.19]
CONT_COAST       = [-0.19, -0.11]
CONT_NEAR_INLAND = [-0.11, 0.03]
CONT_MID_INLAND  = [0.03, 0.3]
CONT_FAR_INLAND  = [0.3, 1.0]
CONT_INLAND      = [-0.11, 0.55]

OCEANS = [
    [mc('deep_frozen_ocean'), mc('deep_cold_ocean'), mc('deep_ocean'),
     mc('deep_lukewarm_ocean'), mc('warm_ocean')],
    [mc('frozen_ocean'), mc('cold_ocean'), mc('ocean'), mc('lukewarm_ocean'), mc('warm_ocean')],
]
MIDDLE = [
    [mc('snowy_plains'),  mc('snowy_plains'),  mc('snowy_plains'), mc('snowy_taiga'), mc('taiga')],
    [mc('plains'),        mc('plains'),         mc('forest'),       mc('taiga'),       mc('old_growth_spruce_taiga')],
    [mc('flower_forest'), mc('plains'),         mc('forest'),       mc('birch_forest'),mc('dark_forest')],
    [mc('savanna'),       mc('savanna'),         mc('forest'),       mc('jungle'),      mc('jungle')],
    [mc('desert'),        mc('desert'),          mc('desert'),       mc('desert'),      mc('desert')],
]
MIDDLE_VARIANT = [
    [mc('ice_spikes'), None, mc('snowy_taiga'), None, None],
    [None, None, None, None, mc('old_growth_pine_taiga')],
    [mc('sunflower_plains'), None, None, mc('old_growth_birch_forest'), None],
    [None, None, mc('plains'), mc('sparse_jungle'), mc('bamboo_jungle')],
    [None]*5,
]
PLATEAU = [
    [mc('snowy_plains'),    mc('snowy_plains'),    mc('snowy_plains'),  mc('snowy_taiga'),     mc('snowy_taiga')],
    [mc('meadow'),          mc('meadow'),           mc('forest'),        mc('taiga'),           mc('old_growth_spruce_taiga')],
    [mc('meadow'),          mc('meadow'),           mc('meadow'),        mc('meadow'),          mc('dark_forest')],
    [mc('savanna_plateau'), mc('savanna_plateau'),  mc('forest'),        mc('forest'),          mc('jungle')],
    [mc('badlands'),        mc('badlands'),         mc('badlands'),      mc('wooded_badlands'), mc('wooded_badlands')],
]
PLATEAU_VARIANT = [
    [mc('ice_spikes'), None, None, None, None],
    [mc('cherry_grove'), None, mc('meadow'), mc('meadow'), mc('old_growth_pine_taiga')],
    [mc('cherry_grove'), mc('cherry_grove'), mc('forest'), mc('birch_forest'), None],
    [None]*5,
    [mc('eroded_badlands'), mc('eroded_badlands'), None, None, None],
]
EXTREME_HILLS = [
    [mc('windswept_gravelly_hills')]*2 + [mc('windswept_hills')] + [mc('windswept_forest')]*2,
    [mc('windswept_gravelly_hills')]*2 + [mc('windswept_hills')] + [mc('windswept_forest')]*2,
    [mc('windswept_hills')]*3 + [mc('windswept_forest')]*2,
    [None]*5, [None]*5,
]
SWAMP_VANILLA = [
    [mc('frozen_river')]*5,
    [mc('swamp')]*5,
    [mc('swamp')]*5,
    [mc('mangrove_swamp')]*5,
    [mc('mangrove_swamp')]*5,
]
BEACH_VANILLA = [
    [mc('snowy_beach')]*5,
    [mc('beach')]*5, [mc('beach')]*5, [mc('beach')]*5,
    [mc('desert')]*5,
]
