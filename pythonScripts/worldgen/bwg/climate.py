def span(a, b): return [a[0], b[1]]
def point(v):   return [v, v]

def mc(name):  return f'minecraft:{name}'
def bwg(name): return f'biomeswevegone:{name}'

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
EXTREME_HILLS_VANILLA = [
    [mc('windswept_gravelly_hills'), mc('windswept_gravelly_hills'), mc('windswept_hills'), mc('windswept_forest'), mc('windswept_forest')],
    [mc('windswept_gravelly_hills'), mc('windswept_gravelly_hills'), mc('windswept_hills'), mc('windswept_forest'), mc('windswept_forest')],
    [mc('windswept_hills'),          mc('windswept_hills'),          mc('windswept_hills'), mc('windswept_forest'), mc('windswept_forest')],
    [None]*5, [None]*5,
]
