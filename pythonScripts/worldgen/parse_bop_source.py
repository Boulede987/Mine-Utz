"""
parse_bop_source.py
====================
Simulates BOPOverworldBiomeBuilder (primary, rare, secondary) to produce
noise parameter entries for BiomesOPlenty biomes.

Output: worldgen_data/biomesoplenty.json
Structure mirrors extract_noise_to_json.py output.

Run from repo root: python pythonScripts/worldgen/parse_bop_source.py
"""

import json
from pathlib import Path
from collections import defaultdict

OUT_DIR = Path('worldgen_data')

# ── Climate helpers ───────────────────────────────────────────────────────────

def span(a, b): return [a[0], b[1]]
def point(v):   return [v, v]

# ── Climate parameter arrays (from BOPOverworldBiomeBuilder) ──────────────────

TEMPERATURES = [[-1.0, -0.45], [-0.45, -0.15], [-0.15, 0.2], [0.2, 0.55], [0.55, 1.0]]
HUMIDITIES   = [[-1.0, -0.35], [-0.35, -0.1], [-0.1, 0.1], [0.1, 0.3], [0.3, 1.0]]
EROSIONS     = [[-1.0, -0.78], [-0.78, -0.375], [-0.375, -0.2225],
                [-0.2225, 0.05], [0.05, 0.45], [0.45, 0.55], [0.55, 1.0]]

FULL      = [-1.0, 1.0]
C_MUSH    = [-1.2, -1.05]
C_DOCEAN  = [-1.05, -0.455]
C_OCEAN   = [-0.455, -0.19]
C_COAST   = [-0.19, -0.11]
C_NEAR    = [-0.11, 0.03]
C_MID     = [0.03, 0.3]
C_FAR     = [0.3, 1.0]
C_INLAND  = [-0.11, 0.55]   # span(near, mid)

def mc(n):  return f'minecraft:{n}'
def bop(n): return f'biomesoplenty:{n}'

# ── Vanilla biome arrays ──────────────────────────────────────────────────────

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
MIDDLE_V = [
    [mc('ice_spikes'), None, mc('snowy_taiga'), None, None],
    [None, None, None, None, mc('old_growth_pine_taiga')],
    [mc('sunflower_plains'), None, None, mc('old_growth_birch_forest'), None],
    [None, None, mc('plains'), mc('sparse_jungle'), mc('bamboo_jungle')],
    [None]*5,
]
PLATEAU = [
    [mc('snowy_plains'),    mc('snowy_plains'),    mc('snowy_plains'),  mc('snowy_taiga'),          mc('snowy_taiga')],
    [mc('meadow'),          mc('meadow'),           mc('forest'),        mc('taiga'),                mc('old_growth_spruce_taiga')],
    [mc('meadow'),          mc('meadow'),           mc('meadow'),        mc('meadow'),               mc('dark_forest')],
    [mc('savanna_plateau'), mc('savanna_plateau'),  mc('forest'),        mc('forest'),               mc('jungle')],
    [mc('badlands'),        mc('badlands'),         mc('badlands'),      mc('wooded_badlands'),      mc('wooded_badlands')],
]
PLATEAU_V = [
    [mc('ice_spikes'), None, None, None, None],
    [mc('cherry_grove'), None, mc('meadow'), mc('meadow'), mc('old_growth_pine_taiga')],
    [mc('cherry_grove'), mc('cherry_grove'), mc('forest'), mc('birch_forest'), None],
    [None]*5,
    [mc('eroded_badlands'), mc('eroded_badlands'), None, None, None],
]
EXTREME = [
    [mc('windswept_gravelly_hills')]*2 + [mc('windswept_hills')] + [mc('windswept_forest')]*2,
    [mc('windswept_gravelly_hills')]*2 + [mc('windswept_hills')] + [mc('windswept_forest')]*2,
    [mc('windswept_hills')]*3 + [mc('windswept_forest')]*2,
    [None]*5, [None]*5,
]
SWAMP_V = [
    [mc('frozen_river')]*5,
    [mc('swamp')]*5,
    [mc('swamp')]*5,
    [mc('mangrove_swamp')]*5,
    [mc('mangrove_swamp')]*5,
]
BEACH_V = [
    [mc('snowy_beach')]*5,
    [mc('beach')]*5, [mc('beach')]*5, [mc('beach')]*5,
    [mc('desert')]*5,
]

# ── Primary builder arrays ────────────────────────────────────────────────────

P_MIDDLE = [
    [bop('cold_desert'),      bop('cold_desert'),      bop('snowy_coniferous_forest'), bop('snowy_coniferous_forest'), bop('snowy_coniferous_forest')],
    [bop('coniferous_forest'),bop('coniferous_forest'),bop('coniferous_forest'),       bop('field'),                   bop('field')],
    [bop('pasture'),          bop('prairie'),           bop('redwood_forest'),          bop('overgrown_greens'),        bop('overgrown_greens')],
    [bop('scrubland'),        bop('scrubland'),          bop('woodland'),                bop('rainforest'),              bop('rainforest')],
    [bop('dryland'),          bop('dryland'),            bop('lush_desert'),             bop('lush_desert'),             bop('lush_desert')],
]
P_MIDDLE_V = [
    [None, None, bop('snowy_fir_clearing'), bop('snowy_fir_clearing'), bop('snowy_maple_woods')],
    [None, bop('fir_clearing'), None, None, bop('forested_field')],
    [None, None, None, None, bop('grassland')],
    [None, None, bop('old_growth_woodland'), None, bop('rocky_rainforest')],
    [None, None, None, bop('lush_savanna'), bop('lush_savanna')],
]
P_PLATEAU = [
    [bop('cold_desert'),       bop('cold_desert'),       bop('snowy_fir_clearing'),  bop('snowy_fir_clearing'),  bop('snowy_coniferous_forest')],
    [bop('coniferous_forest'), bop('coniferous_forest'), bop('highland'),             bop('highland'),            bop('field')],
    [bop('prairie'),           bop('prairie'),            bop('highland'),             bop('highland'),            bop('highland')],
    [bop('scrubland'),         bop('scrubland'),          bop('woodland'),             bop('rainforest'),          bop('rocky_rainforest')],
    [bop('dryland'),           bop('dryland'),            bop('lush_desert'),          bop('lush_desert'),         bop('lush_desert')],
]
P_PLATEAU_V = [
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
P_EXTREME = [
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

# ── Rare builder arrays ───────────────────────────────────────────────────────

R_RARE = [
    [None, None, None, bop('auroral_garden'), None],
    [None, None, bop('ominous_woods'), None, None],
    [None, None, None, bop('mystic_grove'), None],
    [None, None, None, None, bop('fungal_jungle')],
    [None]*5,
]
R_RARE_V = [[None]*5]*5

# ── Secondary builder arrays (shadows parent) ─────────────────────────────────

S_MIDDLE = [
    [bop('tundra'),              bop('tundra'),              bop('muskeg'),          bop('snowy_maple_woods'),  bop('dead_forest')],
    [bop('seasonal_forest'),     bop('seasonal_forest'),     bop('seasonal_forest'), bop('maple_woods'),        bop('maple_woods')],
    [bop('lavender_field'),      bop('lavender_field'),      bop('orchard'),         bop('shrubland'),          bop('shrubland')],
    [bop('mediterranean_forest'),bop('mediterranean_forest'),bop('woodland'),        bop('rainforest'),         bop('volcanic_plains')],
    [bop('wasteland')]*5,
]
S_MIDDLE_V = [
    [None, None, None, bop('snowblossom_grove'), bop('old_growth_dead_forest')],
    [None, bop('aspen_glade'), bop('pumpkin_patch'), None, None],
    [None, bop('jacaranda_glade'), None, bop('rocky_shrubland'), bop('grassland')],
    [None, None, bop('old_growth_woodland'), bop('volcanic_plains'), None],
    [None]*5,
]
S_PLATEAU = [
    [bop('tundra'),              bop('tundra'),              bop('snowblossom_grove'), bop('snowy_maple_woods'), bop('dead_forest')],
    [bop('seasonal_forest'),     bop('aspen_glade'),         bop('highland'),          bop('highland'),          bop('highland')],
    [bop('lavender_field'),      bop('lavender_field'),      bop('orchard'),           bop('rocky_shrubland'),   bop('highland')],
    [bop('mediterranean_forest'),bop('mediterranean_forest'),bop('woodland'),          bop('rocky_rainforest'),  bop('volcano')],
    [bop('wasteland_steppe')]*5,
]
S_PLATEAU_V = [
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

# ── Builder simulation ────────────────────────────────────────────────────────

class BOPBuilder:
    def __init__(self, label='primary'):
        self.label = label
        self.entries = []
        # Default arrays — can be replaced by subclass
        self.MID   = P_MIDDLE;   self.MID_V  = P_MIDDLE_V
        self.PLAT  = P_PLATEAU;  self.PLAT_V = P_PLATEAU_V
        self.SLOP  = P_SLOPE;    self.PEAK   = P_PEAK
        self.XHIL  = P_EXTREME;  self.SWAM   = P_SWAMP
        self.RIVR  = P_RIVER;    self.BEAC   = P_BEACH
        self.STON  = P_STONY;    self.ISLE   = P_ISLAND

    # ── pick helpers ──────────────────────────────────────────────────────────

    @staticmethod
    def _f(*args):
        for a in args:
            if a is not None:
                return a
        return None

    def mid_van(self, ti, hi, w):
        if w[1] < 0: return MIDDLE[ti][hi]
        return self._f(MIDDLE_V[ti][hi], MIDDLE[ti][hi])

    def mid_bop(self, ti, hi, w):
        base = self._f(self.MID[ti][hi], MIDDLE[ti][hi])
        if w[1] < 0: return base
        return self._f(self.MID_V[ti][hi], base)

    def mid_or_slope(self, ti, hi, w):
        return self.slope(ti, hi, w) if ti == 0 else self.mid_bop(ti, hi, w)

    def shattered(self, ti, hi, w, fallback):
        if ti > 1 and hi < 4 and w[1] >= 0:
            return self._f(bop('crag'), mc('windswept_savanna'))
        return fallback

    def swamp_van(self, ti, hi, w):
        r = SWAMP_V[ti][hi]
        return r if r else self.mid_van(ti, hi, w)

    def swamp(self, ti, hi, w):
        return self._f(self.SWAM[ti][hi], self.swamp_van(ti, hi, w))

    def river(self, ti, hi):
        return self._f(self.RIVR[ti][hi], mc('frozen_river') if ti == 0 else mc('river'))

    def beach(self, ti, hi):
        return self._f(self.BEAC[ti][hi], BEACH_V[ti][hi])

    def stony(self, ti, hi):
        return self._f(self.STON[ti][hi], mc('stony_shore'))

    def island(self, ti, hi):
        return self._f(self.ISLE[ti][hi], mc('mushroom_fields'))

    def badlands(self, hi, w):
        if hi < 2: return mc('eroded_badlands') if w[1] < 0 else mc('badlands')
        return mc('badlands') if hi < 3 else mc('wooded_badlands')

    def plateau(self, ti, hi, w):
        if w[1] < 0:
            return self._f(self.PLAT[ti][hi], PLATEAU[ti][hi])
        return self._f(self.PLAT_V[ti][hi], self.PLAT[ti][hi], PLATEAU_V[ti][hi], PLATEAU[ti][hi])

    def peak_van(self, ti, hi, w):
        if ti <= 2: return mc('jagged_peaks') if w[1] < 0 else mc('frozen_peaks')
        if ti == 3: return mc('stony_peaks')
        return self.badlands(hi, w)

    def peak(self, ti, hi, w):
        return self._f(self.PEAK[ti][hi], self.peak_van(ti, hi, w))

    def slope(self, ti, hi, w):
        return self._f(self.SLOP[ti][hi], self.plateau(ti, hi, w))

    def extreme_van(self, ti, hi, w):
        r = EXTREME[ti][hi]
        return r if r else self.mid_van(ti, hi, w)

    def extreme(self, ti, hi, w):
        return self._f(self.XHIL[ti][hi], self.extreme_van(ti, hi, w))

    def shattered_coast(self, ti, hi, w):
        rk = self.mid_van(ti, hi, w) if w[1] >= 0 else self.beach(ti, hi)
        return self.shattered(ti, hi, w, rk)

    # ── emit helpers ──────────────────────────────────────────────────────────

    def surf(self, t, h, c, e, w, biome):
        if biome is None: return
        for d in ([0.0, 0.0], [1.0, 1.0]):
            self.entries.append({'biome': biome, 'parameters': {
                'temperature': t, 'humidity': h, 'continentalness': c,
                'erosion': e, 'depth': d, 'weirdness': w, 'offset': 0.0}})

    def cave(self, t, h, c, e, w, biome):
        if biome is None: return
        self.entries.append({'biome': biome, 'parameters': {
            'temperature': t, 'humidity': h, 'continentalness': c,
            'erosion': e, 'depth': [0.2, 0.9], 'weirdness': w, 'offset': 0.0}})

    def deep(self, t, h, c, e, w, biome):
        if biome is None: return
        self.entries.append({'biome': biome, 'parameters': {
            'temperature': t, 'humidity': h, 'continentalness': c,
            'erosion': e, 'depth': [1.1, 1.1], 'weirdness': w, 'offset': 0.0}})

    # ── slice methods ─────────────────────────────────────────────────────────

    def off_coast(self):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                self.surf(t, HUMIDITIES[hi], C_MUSH, FULL, FULL, self.island(ti, hi))
            self.surf(t, FULL, C_DOCEAN, FULL, FULL, OCEANS[0][ti])
            self.surf(t, FULL, C_OCEAN,  FULL, FULL, OCEANS[1][ti])

    def peaks(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h = HUMIDITIES[hi]
                mid  = self.mid_bop(ti, hi, w)
                mbs  = self.mid_or_slope(ti, hi, w)
                plat = self.plateau(ti, hi, w)
                xv   = self.extreme_van(ti, hi, w)
                xb   = self.extreme(ti, hi, w)
                shat = self.shattered(ti, hi, w, xv)
                pk   = self.peak(ti, hi, w)
                self.surf(t, h, span(C_COAST, C_FAR),  EROSIONS[0],                    w, pk)
                self.surf(t, h, span(C_COAST, C_NEAR),  EROSIONS[1],                   w, mbs)
                self.surf(t, h, span(C_MID,   C_FAR),   EROSIONS[1],                   w, pk)
                self.surf(t, h, span(C_COAST, C_NEAR),  span(EROSIONS[2], EROSIONS[3]),w, mid)
                self.surf(t, h, span(C_MID,   C_FAR),   EROSIONS[2],                   w, plat)
                self.surf(t, h, C_MID,                   EROSIONS[3],                   w, mid)
                self.surf(t, h, C_FAR,                   EROSIONS[3],                   w, plat)
                self.surf(t, h, span(C_COAST, C_FAR),   EROSIONS[4],                   w, mid)
                self.surf(t, h, span(C_COAST, C_NEAR),  EROSIONS[5],                   w, shat)
                self.surf(t, h, span(C_MID,   C_FAR),   EROSIONS[5],                   w, xb)
                self.surf(t, h, span(C_COAST, C_FAR),   EROSIONS[6],                   w, mid)

    def high_slice(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h = HUMIDITIES[hi]
                mv   = self.mid_van(ti, hi, w)
                mid  = self.mid_bop(ti, hi, w)
                mbs  = self.mid_or_slope(ti, hi, w)
                plat = self.plateau(ti, hi, w)
                xb   = self.extreme(ti, hi, w)
                shat = self.shattered(ti, hi, w, mv)
                slop = self.slope(ti, hi, w)
                pk   = self.peak(ti, hi, w)
                self.surf(t, h, C_COAST,                  span(EROSIONS[0], EROSIONS[1]), w, mid)
                self.surf(t, h, C_NEAR,                   EROSIONS[0],                    w, slop)
                self.surf(t, h, span(C_MID, C_FAR),       EROSIONS[0],                    w, pk)
                self.surf(t, h, C_NEAR,                   EROSIONS[1],                    w, mbs)
                self.surf(t, h, span(C_MID, C_FAR),       EROSIONS[1],                    w, slop)
                self.surf(t, h, span(C_COAST, C_NEAR),    span(EROSIONS[2], EROSIONS[3]), w, mid)
                self.surf(t, h, span(C_MID, C_FAR),       EROSIONS[2],                    w, plat)
                self.surf(t, h, C_MID,                    EROSIONS[3],                    w, mid)
                self.surf(t, h, C_FAR,                    EROSIONS[3],                    w, plat)
                self.surf(t, h, span(C_COAST, C_FAR),     EROSIONS[4],                    w, mid)
                self.surf(t, h, span(C_COAST, C_NEAR),    EROSIONS[5],                    w, shat)
                self.surf(t, h, span(C_MID, C_FAR),       EROSIONS[5],                    w, xb)
                self.surf(t, h, span(C_COAST, C_FAR),     EROSIONS[6],                    w, mid)

    def mid_slice(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h = HUMIDITIES[hi]
                mv   = self.mid_van(ti, hi, w)
                mid  = self.mid_bop(ti, hi, w)
                mbs  = self.mid_or_slope(ti, hi, w)
                xb   = self.extreme(ti, hi, w)
                plat = self.plateau(ti, hi, w)
                beac = self.beach(ti, hi)
                ston = self.stony(ti, hi)
                shat = self.shattered(ti, hi, w, mv)
                sc   = self.shattered_coast(ti, hi, w)
                slop = self.slope(ti, hi, w)
                swam = self.swamp(ti, hi, w)
                self.surf(t, h, C_COAST,               span(EROSIONS[0], EROSIONS[2]), w, ston)
                self.surf(t, h, span(C_NEAR, C_FAR),   EROSIONS[0],                    w, slop)
                self.surf(t, h, span(C_NEAR, C_MID),   EROSIONS[1],                    w, mbs)
                self.surf(t, h, C_FAR,                 EROSIONS[1],     w, slop if ti == 0 else plat)
                self.surf(t, h, C_NEAR,                EROSIONS[2],                    w, mid)
                self.surf(t, h, C_MID,                 EROSIONS[2],                    w, mid)
                self.surf(t, h, C_FAR,                 EROSIONS[2],                    w, plat)
                self.surf(t, h, span(C_COAST, C_NEAR), EROSIONS[3],                    w, mid)
                self.surf(t, h, span(C_MID, C_FAR),    EROSIONS[3],                    w, mid)
                if w[1] < 0:
                    self.surf(t, h, C_COAST,               EROSIONS[4], w, beac)
                    self.surf(t, h, span(C_NEAR, C_FAR),   EROSIONS[4], w, mid)
                else:
                    self.surf(t, h, span(C_COAST, C_FAR),  EROSIONS[4], w, mid)
                self.surf(t, h, C_COAST,               EROSIONS[5], w, sc)
                self.surf(t, h, C_NEAR,                EROSIONS[5], w, shat)
                self.surf(t, h, span(C_MID, C_FAR),    EROSIONS[5], w, xb)
                if w[1] < 0:
                    self.surf(t, h, C_COAST,           EROSIONS[6], w, beac)
                else:
                    self.surf(t, h, C_COAST,           EROSIONS[6], w, mid)
                self.surf(t, h, span(C_NEAR, C_FAR),   EROSIONS[6], w, swam)

    def low_slice(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h = HUMIDITIES[hi]
                mv   = self.mid_van(ti, hi, w)
                mid  = self.mid_bop(ti, hi, w)
                mbs  = self.mid_or_slope(ti, hi, w)
                beac = self.beach(ti, hi)
                ston = self.stony(ti, hi)
                shat = self.shattered(ti, hi, w, mv)
                sc   = self.shattered_coast(ti, hi, w)
                swam = self.swamp(ti, hi, w)
                self.surf(t, h, C_COAST,              span(EROSIONS[0], EROSIONS[2]),  w, ston)
                self.surf(t, h, C_NEAR,               span(EROSIONS[0], EROSIONS[1]),  w, mid)
                self.surf(t, h, span(C_MID, C_FAR),   span(EROSIONS[0], EROSIONS[1]),  w, mbs)
                self.surf(t, h, C_NEAR,               span(EROSIONS[2], EROSIONS[3]),  w, mid)
                self.surf(t, h, span(C_MID, C_FAR),   span(EROSIONS[2], EROSIONS[3]),  w, mid)
                self.surf(t, h, C_COAST,              span(EROSIONS[3], EROSIONS[4]),  w, beac)
                self.surf(t, h, span(C_NEAR, C_FAR),  EROSIONS[4],                     w, mid)
                self.surf(t, h, C_COAST,              EROSIONS[5],                     w, sc)
                self.surf(t, h, C_NEAR,               EROSIONS[5],                     w, shat)
                self.surf(t, h, span(C_MID, C_FAR),   EROSIONS[5],                     w, mid)
                self.surf(t, h, C_COAST,              EROSIONS[6],                     w, beac)
                self.surf(t, h, span(C_NEAR, C_FAR),  EROSIONS[6],                     w, swam)

    def valleys(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h    = HUMIDITIES[hi]
                rivr = self.river(ti, hi)
                ston = self.stony(ti, hi)
                self.surf(t, h, C_COAST,             span(EROSIONS[0], EROSIONS[1]), w, ston if w[1] < 0 else rivr)
                self.surf(t, h, C_NEAR,              span(EROSIONS[0], EROSIONS[1]), w, rivr)
                self.surf(t, h, span(C_COAST, C_FAR),span(EROSIONS[2], EROSIONS[5]), w, rivr)
                self.surf(t, h, C_COAST,             EROSIONS[6],                    w, rivr)
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h    = HUMIDITIES[hi]
                mid  = self.mid_bop(ti, hi, w)
                swam = self.swamp(ti, hi, w)
                self.surf(t, h, span(C_MID, C_FAR),    span(EROSIONS[0], EROSIONS[1]), w, mid)
                self.surf(t, h, span(C_INLAND, C_FAR),  EROSIONS[6],                   w, swam)

    def underground(self):
        self.cave(FULL, FULL,       [0.8, 1.0], FULL, FULL, bop('spider_nest'))
        self.cave(FULL, [0.7, 1.0], FULL,       FULL, FULL, bop('glowing_grotto'))
        self.deep(FULL, FULL,       FULL, span(EROSIONS[0], EROSIONS[1]), FULL, mc('deep_dark'))

    # ── full run ──────────────────────────────────────────────────────────────

    INLAND_SLICES = [
        ('mid_slice',  [-1.0, -0.93333334]),
        ('high_slice', [-0.93333334, -0.7666667]),
        ('peaks',      [-0.7666667, -0.56666666]),
        ('high_slice', [-0.56666666, -0.4]),
        ('mid_slice',  [-0.4, -0.26666668]),
        ('low_slice',  [-0.26666668, -0.05]),
        ('valleys',    [-0.05, 0.05]),
        ('low_slice',  [0.05, 0.26666668]),
        ('mid_slice',  [0.26666668, 0.4]),
        ('high_slice', [0.4, 0.56666666]),
        ('peaks',      [0.56666666, 0.7666667]),
        ('high_slice', [0.7666667, 0.93333334]),
        ('mid_slice',  [0.93333334, 1.0]),
    ]

    def run(self):
        self.off_coast()
        for method_name, w in self.INLAND_SLICES:
            getattr(self, method_name)(w)
        self.underground()
        return self.entries


class BOPRareBuilder(BOPBuilder):
    def __init__(self):
        super().__init__('rare')

    def mid_bop(self, ti, hi, w):
        base = self._f(R_RARE[ti][hi], self.MID[ti][hi], MIDDLE[ti][hi])
        if w[1] < 0: return base
        return self._f(R_RARE_V[ti][hi], R_RARE[ti][hi], self.MID_V[ti][hi], base)

    def plateau(self, ti, hi, w):
        if w[1] < 0:
            return self._f(R_RARE[ti][hi], self.PLAT[ti][hi], PLATEAU[ti][hi])
        return self._f(R_RARE_V[ti][hi], R_RARE[ti][hi], self.PLAT_V[ti][hi],
                       self.PLAT[ti][hi], PLATEAU_V[ti][hi], PLATEAU[ti][hi])

    def river(self, ti, hi):
        return self._f(R_RARE[ti][hi], self.RIVR[ti][hi],
                       mc('frozen_river') if ti == 0 else mc('river'))

    def swamp(self, ti, hi, w):
        return self._f(R_RARE[ti][hi], self.SWAM[ti][hi], self.swamp_van(ti, hi, w))

    def beach(self, ti, hi):
        return self._f(R_RARE[ti][hi], self.BEAC[ti][hi], BEACH_V[ti][hi])

    def stony(self, ti, hi):
        return self._f(R_RARE[ti][hi], self.STON[ti][hi], mc('stony_shore'))


class BOPSecondaryBuilder(BOPBuilder):
    def __init__(self):
        super().__init__('secondary')
        self.MID    = S_MIDDLE;    self.MID_V  = S_MIDDLE_V
        self.PLAT   = S_PLATEAU;   self.PLAT_V = S_PLATEAU_V
        self.SWAM   = S_SWAMP;     self.RIVR   = S_RIVER
        self.BEAC   = S_BEACH;     self.STON   = S_STONY

# ── depth section (mirrors extract_noise_to_json.py) ─────────────────────────

def depth_section(depth):
    d_min, d_max = depth[0], depth[1]
    if d_max <= 0.05:   return 'surface'
    if d_min >= 1.05:   return 'deep'
    if d_min >= 0.15:   return 'caves'
    return 'underground'

# ── run all builders and collect ──────────────────────────────────────────────

all_entries = []
for BuilderClass in (BOPBuilder, BOPRareBuilder, BOPSecondaryBuilder):
    all_entries.extend(BuilderClass().run())

# filter to BOP biomes only, deduplicate
seen = set()
bop_entries = []
for e in all_entries:
    if not e['biome'].startswith('biomesoplenty:'):
        continue
    p = e['parameters']
    key = (e['biome'],
           tuple(p['temperature']), tuple(p['humidity']),
           tuple(p['continentalness']), tuple(p['erosion']),
           tuple(p['depth']), tuple(p['weirdness']), p['offset'])
    if key in seen:
        continue
    seen.add(key)
    bop_entries.append(e)

# group by depth section, sort
sections = {'surface': [], 'underground': [], 'caves': [], 'deep': []}
for e in bop_entries:
    sections[depth_section(e['parameters']['depth'])].append(e)
for lst in sections.values():
    lst.sort(key=lambda e: e['biome'])

# ── write output ──────────────────────────────────────────────────────────────

OUT_DIR.mkdir(exist_ok=True)
out = {
    'namespace': 'biomesoplenty',
    'dimensions': {
        'minecraft:overworld': sections
    }
}

totals = {k: len(v) for k, v in sections.items()}
print(f"BiomesOPlenty overworld entries:  {totals}")
print(f"  surface: {totals['surface']}  underground: {totals['underground']}  caves: {totals['caves']}  deep: {totals['deep']}")

path = OUT_DIR / 'biomesoplenty.json'
path.write_text(json.dumps(out, indent=2), encoding='utf-8')
print(f"\nWritten -> {path}")
