"""
parse_bwg_source.py
====================
Simulates BWGTerraBlenderRegion (regions 1, 2, 3) to produce
noise parameter entries for Oh The Biomes We've Gone biomes.

BWGTerraBlenderRegion uses TerrablenderOverworldBiomeBuilder
(which extends vanilla's OverworldBiomeBuilder) with custom
2D biome arrays. Also applies a biome swapper for swamp biomes.

Output: worldgen_data/biomeswevegone.json

Run from repo root: python pythonScripts/worldgen/parse_bwg_source.py
"""

import json
from pathlib import Path
from collections import defaultdict

OUT_DIR = Path('worldgen_data')

# ── Climate helpers ───────────────────────────────────────────────────────────

def span(a, b): return [a[0], b[1]]
def point(v):   return [v, v]

# ── Climate parameter arrays ──────────────────────────────────────────────────

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
C_INLAND  = [-0.11, 0.55]

def mc(n):  return f'minecraft:{n}'
def bwg(n): return f'biomeswevegone:{n}'

# ── Vanilla swamp/beach arrays (base for fallback) ────────────────────────────

SWAMP_VAN = [
    [mc('frozen_river')]*5,   # temp 0
    [mc('swamp')]*5,          # temp 1
    [mc('swamp')]*5,          # temp 2
    [mc('mangrove_swamp')]*5, # temp 3
    [mc('mangrove_swamp')]*5, # temp 4
]
BEACH_VAN = [
    [mc('snowy_beach')]*5,
    [mc('beach')]*5, [mc('beach')]*5, [mc('beach')]*5,
    [mc('desert')]*5,
]

# ── Vanilla EXTREME_HILLS (= SHATTERED when not overridden) ──────────────────

EXTREME_VAN = [
    [mc('windswept_gravelly_hills'), mc('windswept_gravelly_hills'), mc('windswept_hills'), mc('windswept_forest'), mc('windswept_forest')],
    [mc('windswept_gravelly_hills'), mc('windswept_gravelly_hills'), mc('windswept_hills'), mc('windswept_forest'), mc('windswept_forest')],
    [mc('windswept_hills'), mc('windswept_hills'), mc('windswept_hills'), mc('windswept_forest'), mc('windswept_forest')],
    [None]*5, [None]*5,
]

# ── None sentinel: DEFERRED_PLACEHOLDER or THE_VOID ──────────────────────────
# Both mean "vanilla biome in this slot" — we treat as None.

DEF = None  # DEFERRED_PLACEHOLDER or THE_VOID

# ── BWGBiomeSelectors arrays (from decompiled source) ─────────────────────────

# ── Oceans ────────────────────────────────────────────────────────────────────
OCEANS_BWG = [  # OCEANS_BWG and OCEANS_2_BWG are identical
    [DEF, DEF, DEF, bwg('lush_stacks'), bwg('dead_sea')],  # deep
    [DEF, DEF, DEF, bwg('lush_stacks'), bwg('dead_sea')],  # shallow
]
OCEANS_VAN = [[DEF]*5, [DEF]*5]  # OCEANS_TERRABLENDER: all vanilla

# ── Middle biomes ─────────────────────────────────────────────────────────────
MIDDLE_1 = [  # MIDDLE_BIOMES_BWG
    [bwg('shattered_glacier'), bwg('crimson_tundra'),      bwg('crimson_tundra'),          bwg('frosted_coniferous_forest'), bwg('coniferous_forest')],
    [bwg('pumpkin_valley'),    bwg('pumpkin_valley'),      bwg('skyris_vale'),             bwg('skyris_vale'),               bwg('cika_woods')],
    [bwg('prairie'),           bwg('allium_shrubland'),    bwg('allium_shrubland'),        bwg('temperate_grove'),           bwg('temperate_grove')],
    [bwg('araucaria_savanna'), bwg('ironwood_gour'),       bwg('amaranth_grassland'),      bwg('ebony_woods'),               bwg('jacaranda_jungle')],
    [bwg('mojave_desert'),     bwg('mojave_desert'),       bwg('windswept_desert'),        bwg('atacama_outback'),           bwg('red_rock_valley')],
]
MIDDLE_2 = [  # MIDDLE_BIOMES_2_BWG
    [bwg('shattered_glacier'), bwg('crimson_tundra'),      bwg('frosted_coniferous_forest'),bwg('eroded_borealis'),          bwg('coniferous_forest')],
    [bwg('coconino_meadow'),   bwg('coconino_meadow'),     bwg('maple_taiga'),             bwg('dacite_ridges'),             bwg('dacite_ridges')],
    [bwg('prairie'),           bwg('orchard'),             bwg('orchard'),                 bwg('aspen_boreal'),              bwg('weeping_witch_forest')],
    [bwg('baobab_savanna'),    bwg('baobab_savanna'),      bwg('amaranth_grassland'),      bwg('crag_gardens'),              bwg('tropical_rainforest')],
    [bwg('mojave_desert'),     bwg('rugged_badlands'),     bwg('windswept_desert'),        bwg('atacama_outback'),           bwg('red_rock_valley')],
]
MIDDLE_3 = [  # MIDDLE_BIOMES_3_BWG
    [bwg('shattered_glacier'), bwg('shattered_glacier'),   bwg('frosted_coniferous_forest'),bwg('frosted_coniferous_forest'),bwg('coniferous_forest')],
    [bwg('coconino_meadow'),   bwg('coconino_meadow'),     bwg('maple_taiga'),             bwg('zelkova_forest'),            bwg('zelkova_forest')],
    [bwg('prairie'),           bwg('prairie'),             bwg('allium_shrubland'),        bwg('aspen_boreal'),              bwg('weeping_witch_forest')],
    [bwg('firecracker_chaparral'),bwg('ironwood_gour'),    bwg('amaranth_grassland'),      bwg('enchanted_tangle'),          bwg('fragment_jungle')],
    [bwg('mojave_desert'),     bwg('rugged_badlands'),     bwg('windswept_desert'),        bwg('atacama_outback'),           bwg('red_rock_valley')],
]
MIDDLE_V_BWG = [  # MIDDLE_BIOMES_VARIANT_BWG — sparse (rest are vanilla/THE_VOID)
    [DEF, DEF, DEF, DEF, DEF],
    [DEF, DEF, bwg('forgotten_forest'), DEF, DEF],
    [DEF, DEF, bwg('forgotten_forest'), DEF, DEF],
    [DEF, DEF, DEF, bwg('crag_gardens'), bwg('crag_gardens')],
    [DEF]*5,
]
MIDDLE_V_VAN = [[DEF]*5]*5  # MIDDLE_BIOMES_VARIANT_TERRABLENDER: all vanilla

# ── Plateau biomes ────────────────────────────────────────────────────────────
PLATEAU_1 = [  # PLATEAU_BIOMES_BWG
    [bwg('shattered_glacier'), bwg('shattered_glacier'), bwg('frosted_coniferous_forest'), bwg('frosted_taiga'),     bwg('coniferous_forest')],
    [bwg('coconino_meadow'),   bwg('coconino_meadow'),  bwg('aspen_boreal'),              bwg('skyris_vale'),       bwg('skyris_vale')],
    [bwg('temperate_grove'),   bwg('temperate_grove'),  bwg('temperate_grove'),            bwg('rose_fields'),       bwg('dacite_ridges')],
    [bwg('baobab_savanna'),    bwg('baobab_savanna'),   bwg('redwood_thicket'),            bwg('jacaranda_jungle'),  bwg('crag_gardens')],
    [bwg('sierra_badlands'),   bwg('sierra_badlands'),  bwg('sierra_badlands'),            bwg('red_rock_valley'),   bwg('red_rock_valley')],
]
PLATEAU_2 = [  # PLATEAU_BIOMES_2_BWG
    [bwg('shattered_glacier'), bwg('shattered_glacier'), bwg('frosted_coniferous_forest'), bwg('frosted_taiga'),     bwg('coniferous_forest')],
    [bwg('coconino_meadow'),   bwg('coconino_meadow'),  bwg('black_forest'),              bwg('black_forest'),      bwg('black_forest')],
    [bwg('sakura_grove'),      bwg('sakura_grove'),     bwg('sakura_grove'),               bwg('rose_fields'),       bwg('weeping_witch_forest')],
    [bwg('araucaria_savanna'), bwg('araucaria_savanna'),bwg('redwood_thicket'),            bwg('ebony_woods'),       bwg('tropical_rainforest')],
    [bwg('sierra_badlands'),   bwg('sierra_badlands'),  bwg('sierra_badlands'),            bwg('red_rock_valley'),   bwg('red_rock_valley')],
]
PLATEAU_3 = [  # PLATEAU_BIOMES_3_BWG
    [bwg('shattered_glacier'), bwg('shattered_glacier'), bwg('frosted_coniferous_forest'), bwg('frosted_taiga'),     bwg('coniferous_forest')],
    [bwg('coconino_meadow'),   bwg('coconino_meadow'),  bwg('cika_woods'),                bwg('cika_woods'),        bwg('cika_woods')],
    [bwg('temperate_grove'),   bwg('temperate_grove'),  bwg('temperate_grove'),            bwg('rose_fields'),       bwg('weeping_witch_forest')],
    [bwg('firecracker_chaparral'),bwg('firecracker_chaparral'),bwg('redwood_thicket'),    bwg('ebony_woods'),       bwg('enchanted_tangle')],
    [bwg('sierra_badlands'),   bwg('sierra_badlands'),  bwg('sierra_badlands'),            bwg('red_rock_valley'),   bwg('red_rock_valley')],
]
PLATEAU_V_BWG = [  # PLATEAU_BIOMES_VARIANT_BWG
    [DEF, DEF, DEF, DEF, DEF],
    [bwg('sakura_grove'), bwg('overgrowth_woodlands'), bwg('overgrowth_woodlands'), DEF, DEF],
    [bwg('sakura_grove'), bwg('sakura_grove'), DEF, DEF, DEF],
    [DEF, DEF, DEF, DEF, bwg('crag_gardens')],
    [DEF, DEF, DEF, DEF, DEF],
]
PLATEAU_V_VAN = [[DEF]*5]*5  # vanilla

# ── Peak biomes ───────────────────────────────────────────────────────────────
PEAK_BWG = [  # PEAK_BIOMES_BWG
    [DEF, bwg('howling_peaks'), bwg('eroded_borealis'), bwg('eroded_borealis'), bwg('eroded_borealis')],
    [DEF, bwg('howling_peaks'), bwg('eroded_borealis'), bwg('eroded_borealis'), bwg('eroded_borealis')],
    [DEF, bwg('howling_peaks'), bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('howling_peaks')],
    [DEF, DEF,                  bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('crag_gardens')],
    [bwg('sierra_badlands'), bwg('sierra_badlands'), bwg('sierra_badlands'), bwg('red_rock_peaks'), bwg('red_rock_peaks')],
]
PEAK_V_BWG = [  # PEAK_BIOMES_VARIANT_BWG
    [DEF, bwg('howling_peaks'), bwg('eroded_borealis'), bwg('eroded_borealis'), bwg('howling_peaks')],
    [DEF, bwg('howling_peaks'), bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('howling_peaks')],
    [DEF, bwg('howling_peaks'), bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('howling_peaks')],
    [DEF, bwg('howling_peaks'), bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('crag_gardens')],
    [bwg('sierra_badlands'), bwg('sierra_badlands'), bwg('sierra_badlands'), bwg('red_rock_peaks'), bwg('red_rock_peaks')],
]
PEAK_VAN = [[DEF]*5]*5

# ── Slope biomes ──────────────────────────────────────────────────────────────
SLOPE_BWG = [  # SLOPE_BIOMES_BWG
    [bwg('shattered_glacier'), bwg('eroded_borealis'),  bwg('eroded_borealis'),           bwg('frosted_taiga'),        bwg('frosted_taiga')],
    [bwg('shattered_glacier'), bwg('eroded_borealis'),  bwg('canadian_shield'),           bwg('coniferous_forest'),    bwg('frosted_coniferous_forest')],
    [bwg('shattered_glacier'), bwg('howling_peaks'),    bwg('canadian_shield'),           bwg('coniferous_forest'),    bwg('frosted_coniferous_forest')],
    [bwg('sierra_badlands'),   bwg('sierra_badlands'),  bwg('redwood_thicket'),           bwg('redwood_thicket'),      bwg('crag_gardens')],
    [bwg('sierra_badlands'),   bwg('sierra_badlands'),  bwg('sierra_badlands'),           bwg('red_rock_peaks'),       bwg('red_rock_peaks')],
]
SLOPE_V_VAN = [  # SLOPE_BIOMES_VARIANT_TERRABLENDER (used in all 3 regions)
    [DEF]*5, [DEF]*5, [DEF]*5,
    [DEF, DEF, DEF, DEF, DEF],        # SLOPE_V[3][*] = THE_VOID
    [DEF, DEF, DEF, DEF, DEF],        # SLOPE_V[4][*] partially THE_VOID
]

# ── Shattered biomes ──────────────────────────────────────────────────────────
SHATTERED_BWG = [  # SHATTERED_BIOMES_BWG
    [DEF]*5, [DEF]*5, [DEF]*5,         # temp 0,1,2: DEFERRED (vanilla)
    [None]*5, [None]*5,                 # temp 3,4: THE_VOID (remove/vanilla)
]
SHATTERED_VAN = [[DEF]*5]*5  # vanilla

# ── Beach biomes ──────────────────────────────────────────────────────────────
BEACH_BWG = [  # BEACH_BIOMES_BWG
    [DEF]*5,
    [mc('beach'), mc('beach'), mc('beach'), bwg('dacite_shore'), bwg('dacite_shore')],
    [bwg('basalt_barrera')]*5,
    [bwg('rainbow_beach')]*5,
    [DEF]*5,
]
BEACH_VAN_TB = [[DEF]*5]*5  # BEACH_BIOMES_TERRABLENDER: all vanilla

# ── Builder class ─────────────────────────────────────────────────────────────

class BWGBuilder:
    def __init__(self, label, oceans, middle, middle_v, plateau, plateau_v,
                 shattered, beach, peak, peak_v, slope, slope_v, swapper):
        self.label    = label
        self.OCEANS   = oceans
        self.MID      = middle;   self.MID_V   = middle_v
        self.PLAT     = plateau;  self.PLAT_V  = plateau_v
        self.SHAT     = shattered
        self.BEACH    = beach
        self.PEAK     = peak;     self.PEAK_V  = peak_v
        self.SLOPE    = slope;    self.SLOPE_V = slope_v
        self.swapper  = swapper   # {vanilla_biome → bwg_biome}
        self.entries  = []

    @staticmethod
    def _f(*args):
        for a in args:
            if a is not None:
                return a
        return None

    # ── Vanilla-like pick methods ─────────────────────────────────────────────

    def mid(self, ti, hi, w):
        base = self.MID[ti][hi]  # None = vanilla, skip
        if w[1] < 0: return base
        v = self.MID_V[ti][hi]
        return v if v is not None else base

    def plat(self, ti, hi, w):
        base = self.PLAT[ti][hi]
        if w[1] < 0: return base
        v = self.PLAT_V[ti][hi]
        return v if v is not None else base

    def peak(self, ti, hi, w):
        base = self.PEAK[ti][hi]
        if w[1] < 0 or base is None: return base
        v = self.PEAK_V[ti][hi]
        return v if v is not None else base

    def slope(self, ti, hi, w):
        base = self.SLOPE[ti][hi]
        if w[1] < 0 or base is None: return base
        v = self.SLOPE_V[ti][hi]
        return v if v is not None else base

    def beach(self, ti, hi):
        b = self.BEACH[ti][hi]
        return b if b is not None else BEACH_VAN[ti][hi]

    def extreme(self, ti, hi, w):
        s = self.SHAT[ti][hi]
        if s is not None: return s
        e = EXTREME_VAN[ti][hi]
        return e if e is not None else self.mid(ti, hi, w)

    def maybe_shattered(self, ti, hi, w, fallback):
        # vanilla: returns WINDSWEPT_SAVANNA for warm+humid<4+positive weirdness
        if ti > 1 and hi < 4 and w[1] >= 0:
            return mc('windswept_savanna')
        return fallback

    def swamp(self, ti, hi, w):
        van = SWAMP_VAN[ti][hi]
        return self.swapper.get(van, van)

    def shattered_coast(self, ti, hi, w):
        rk = self.mid(ti, hi, w) if w[1] >= 0 else self.beach(ti, hi)
        return self.maybe_shattered(ti, hi, w, rk)

    def river(self, ti, hi):
        return mc('frozen_river') if ti == 0 else mc('river')

    # ── Emit helpers ──────────────────────────────────────────────────────────

    def surf(self, t, h, c, e, w, biome):
        if biome is None: return
        for d in ([0.0, 0.0], [1.0, 1.0]):
            self.entries.append({'biome': biome, 'parameters': {
                'temperature': t, 'humidity': h, 'continentalness': c,
                'erosion': e, 'depth': d, 'weirdness': w, 'offset': 0.0}})

    # ── Slice methods (vanilla OverworldBiomeBuilder logic) ───────────────────

    def off_coast(self):
        for ti in range(5):
            t = TEMPERATURES[ti]
            # mushroom fields: vanilla (skip)
            self.surf(t, FULL, C_DOCEAN, FULL, FULL, self.OCEANS[0][ti])
            self.surf(t, FULL, C_OCEAN,  FULL, FULL, self.OCEANS[1][ti])

    def peaks(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h  = HUMIDITIES[hi]
                mid  = self.mid(ti, hi, w)
                plat = self.plat(ti, hi, w)
                xtr  = self.extreme(ti, hi, w)
                pk   = self.peak(ti, hi, w)
                shat = self.maybe_shattered(ti, hi, w, xtr)
                mbs  = self.slope(ti, hi, w) if ti == 0 else mid  # cold → slope
                self.surf(t, h, span(C_COAST, C_FAR),  EROSIONS[0],                    w, pk)
                self.surf(t, h, span(C_COAST, C_NEAR),  EROSIONS[1],                   w, mbs)
                self.surf(t, h, span(C_MID,   C_FAR),   EROSIONS[1],                   w, pk)
                self.surf(t, h, span(C_COAST, C_NEAR),  span(EROSIONS[2], EROSIONS[3]),w, mid)
                self.surf(t, h, span(C_MID,   C_FAR),   EROSIONS[2],                   w, plat)
                self.surf(t, h, C_MID,                  EROSIONS[3],                   w, mid)
                self.surf(t, h, C_FAR,                  EROSIONS[3],                   w, plat)
                self.surf(t, h, span(C_COAST, C_FAR),   EROSIONS[4],                   w, mid)
                self.surf(t, h, span(C_COAST, C_NEAR),  EROSIONS[5],                   w, shat)
                self.surf(t, h, span(C_MID,   C_FAR),   EROSIONS[5],                   w, xtr)
                self.surf(t, h, span(C_COAST, C_FAR),   EROSIONS[6],                   w, mid)

    def high_slice(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h    = HUMIDITIES[hi]
                mid  = self.mid(ti, hi, w)
                plat = self.plat(ti, hi, w)
                xtr  = self.extreme(ti, hi, w)
                slp  = self.slope(ti, hi, w)
                pk   = self.peak(ti, hi, w)
                shat = self.maybe_shattered(ti, hi, w, mid)
                mbs  = slp if ti == 0 else mid
                self.surf(t, h, C_COAST,               span(EROSIONS[0], EROSIONS[1]), w, mid)
                self.surf(t, h, C_NEAR,                EROSIONS[0],                    w, slp)
                self.surf(t, h, span(C_MID, C_FAR),    EROSIONS[0],                    w, pk)
                self.surf(t, h, C_NEAR,                EROSIONS[1],                    w, mbs)
                self.surf(t, h, span(C_MID, C_FAR),    EROSIONS[1],                    w, slp)
                self.surf(t, h, span(C_COAST, C_NEAR), span(EROSIONS[2], EROSIONS[3]), w, mid)
                self.surf(t, h, span(C_MID, C_FAR),    EROSIONS[2],                    w, plat)
                self.surf(t, h, C_MID,                 EROSIONS[3],                    w, mid)
                self.surf(t, h, C_FAR,                 EROSIONS[3],                    w, plat)
                self.surf(t, h, span(C_COAST, C_FAR),  EROSIONS[4],                    w, mid)
                self.surf(t, h, span(C_COAST, C_NEAR), EROSIONS[5],                    w, shat)
                self.surf(t, h, span(C_MID, C_FAR),    EROSIONS[5],                    w, xtr)
                self.surf(t, h, span(C_COAST, C_FAR),  EROSIONS[6],                    w, mid)

    def mid_slice(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h    = HUMIDITIES[hi]
                mid  = self.mid(ti, hi, w)
                plat = self.plat(ti, hi, w)
                xtr  = self.extreme(ti, hi, w)
                slp  = self.slope(ti, hi, w)
                beac = self.beach(ti, hi)
                swam = self.swamp(ti, hi, w)
                shat = self.maybe_shattered(ti, hi, w, mid)
                sc   = self.shattered_coast(ti, hi, w)
                mbs  = slp if ti == 0 else mid
                self.surf(t, h, C_COAST,               span(EROSIONS[0], EROSIONS[2]), w, mc('stony_shore'))
                self.surf(t, h, span(C_NEAR, C_FAR),   EROSIONS[0],                    w, slp)
                self.surf(t, h, span(C_NEAR, C_MID),   EROSIONS[1],                    w, mbs)
                self.surf(t, h, C_FAR,                 EROSIONS[1],  w, slp if ti == 0 else plat)
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
                self.surf(t, h, span(C_MID, C_FAR),    EROSIONS[5], w, xtr)
                if w[1] < 0:
                    self.surf(t, h, C_COAST,           EROSIONS[6], w, beac)
                else:
                    self.surf(t, h, C_COAST,           EROSIONS[6], w, mid)
                self.surf(t, h, span(C_NEAR, C_FAR),   EROSIONS[6], w, swam)

    def low_slice(self, w):
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h    = HUMIDITIES[hi]
                mid  = self.mid(ti, hi, w)
                xtr  = self.extreme(ti, hi, w)
                beac = self.beach(ti, hi)
                swam = self.swamp(ti, hi, w)
                shat = self.maybe_shattered(ti, hi, w, mid)
                sc   = self.shattered_coast(ti, hi, w)
                mbs  = self.slope(ti, hi, w) if ti == 0 else mid
                self.surf(t, h, C_COAST,              span(EROSIONS[0], EROSIONS[2]),  w, mc('stony_shore'))
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
                self.surf(t, h, C_COAST,             span(EROSIONS[0], EROSIONS[1]), w, rivr)
                self.surf(t, h, C_NEAR,              span(EROSIONS[0], EROSIONS[1]), w, rivr)
                self.surf(t, h, span(C_COAST, C_FAR),span(EROSIONS[2], EROSIONS[5]), w, rivr)
                self.surf(t, h, C_COAST,             EROSIONS[6],                    w, rivr)
        for ti in range(5):
            t = TEMPERATURES[ti]
            for hi in range(5):
                h    = HUMIDITIES[hi]
                mid  = self.mid(ti, hi, w)
                swam = self.swamp(ti, hi, w)
                self.surf(t, h, span(C_MID, C_FAR),   span(EROSIONS[0], EROSIONS[1]), w, mid)
                self.surf(t, h, span(C_INLAND, C_FAR), EROSIONS[6],                   w, swam)

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
        return self.entries


# ── Instantiate all 3 regions ─────────────────────────────────────────────────

REGION_1 = BWGBuilder(
    label='region_1',
    oceans=OCEANS_BWG,      middle=MIDDLE_1, middle_v=MIDDLE_V_BWG,
    plateau=PLATEAU_1,       plateau_v=PLATEAU_V_BWG,
    shattered=SHATTERED_BWG, beach=BEACH_BWG,
    peak=PEAK_BWG,           peak_v=PEAK_V_BWG,
    slope=SLOPE_BWG,         slope_v=SLOPE_V_VAN,
    swapper={mc('mangrove_swamp'): bwg('cypress_swamplands'), mc('swamp'): bwg('bayou')},
)
REGION_2 = BWGBuilder(
    label='region_2',
    oceans=OCEANS_BWG,       middle=MIDDLE_2, middle_v=MIDDLE_V_VAN,
    plateau=PLATEAU_2,        plateau_v=PLATEAU_V_VAN,
    shattered=SHATTERED_VAN,  beach=BEACH_VAN_TB,
    peak=PEAK_VAN,            peak_v=PEAK_VAN,
    slope=[[DEF]*5]*5,        slope_v=SLOPE_V_VAN,
    swapper={mc('mangrove_swamp'): bwg('white_mangrove_marshes'), mc('swamp'): bwg('cypress_wetlands')},
)
REGION_3 = BWGBuilder(
    label='region_3',
    oceans=OCEANS_VAN,        middle=MIDDLE_3, middle_v=MIDDLE_V_VAN,
    plateau=PLATEAU_3,         plateau_v=PLATEAU_V_VAN,
    shattered=SHATTERED_VAN,   beach=BEACH_VAN_TB,
    peak=PEAK_VAN,             peak_v=PEAK_VAN,
    slope=[[DEF]*5]*5,         slope_v=SLOPE_V_VAN,
    swapper={mc('mangrove_swamp'): bwg('pale_bog'), mc('swamp'): bwg('cypress_swamplands')},
)

# ── Collect and filter ────────────────────────────────────────────────────────

all_entries = []
for region in (REGION_1, REGION_2, REGION_3):
    all_entries.extend(region.run())

def depth_section(depth):
    d_min, d_max = depth[0], depth[1]
    if d_max <= 0.05:   return 'surface'
    if d_min >= 1.05:   return 'deep'
    if d_min >= 0.15:   return 'caves'
    return 'underground'

seen = set()
bwg_entries = []
for e in all_entries:
    if not e['biome'].startswith('biomeswevegone:'):
        continue
    p = e['parameters']
    key = (e['biome'],
           tuple(p['temperature']), tuple(p['humidity']),
           tuple(p['continentalness']), tuple(p['erosion']),
           tuple(p['depth']), tuple(p['weirdness']), p['offset'])
    if key in seen: continue
    seen.add(key)
    bwg_entries.append(e)

sections = {'surface': [], 'underground': [], 'caves': [], 'deep': []}
for e in bwg_entries:
    sections[depth_section(e['parameters']['depth'])].append(e)
for lst in sections.values():
    lst.sort(key=lambda e: e['biome'])

# ── Write output ──────────────────────────────────────────────────────────────

OUT_DIR.mkdir(exist_ok=True)
out = {'namespace': 'biomeswevegone', 'dimensions': {'minecraft:overworld': sections}}

totals = {k: len(v) for k, v in sections.items()}
unique = len(set(e['biome'] for e in bwg_entries))
print(f"Oh The Biomes We've Gone: {unique} unique biomes, {len(bwg_entries)} total entries")
print(f"  {totals}")

path = OUT_DIR / 'biomeswevegone.json'
path.write_text(json.dumps(out, indent=2), encoding='utf-8')
print(f"\nWritten -> {path}")
