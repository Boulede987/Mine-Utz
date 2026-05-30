from .climate import mc, bwg

# Both DEFERRED_PLACEHOLDER and THE_VOID mean "use vanilla biome in this slot"
DEFERRED = None

# ── Vanilla-slot placeholders (all DEFERRED = use vanilla) ────────────────────

OCEANS_VANILLA              = [[DEFERRED]*5, [DEFERRED]*5]
MIDDLE_VARIANT_VANILLA      = [[DEFERRED]*5]*5
PLATEAU_VARIANT_VANILLA     = [[DEFERRED]*5]*5
PEAK_VANILLA                = [[DEFERRED]*5]*5
SLOPE_VARIANT_VANILLA       = [[DEFERRED]*5]*5
SHATTERED_VANILLA           = [[DEFERRED]*5]*5
BEACH_VANILLA_TERRABLENDER  = [[DEFERRED]*5]*5

# ── Oceans ────────────────────────────────────────────────────────────────────

OCEANS_BWG = [
    [DEFERRED, DEFERRED, DEFERRED, bwg('lush_stacks'), bwg('dead_sea')],  # deep
    [DEFERRED, DEFERRED, DEFERRED, bwg('lush_stacks'), bwg('dead_sea')],  # shallow
]

# ── Middle biomes ─────────────────────────────────────────────────────────────

MIDDLE_1 = [
    [bwg('shattered_glacier'),    bwg('crimson_tundra'),    bwg('crimson_tundra'),           bwg('frosted_coniferous_forest'), bwg('coniferous_forest')],
    [bwg('pumpkin_valley'),       bwg('pumpkin_valley'),    bwg('skyris_vale'),              bwg('skyris_vale'),               bwg('cika_woods')],
    [bwg('prairie'),              bwg('allium_shrubland'),  bwg('allium_shrubland'),         bwg('temperate_grove'),           bwg('temperate_grove')],
    [bwg('araucaria_savanna'),    bwg('ironwood_gour'),     bwg('amaranth_grassland'),       bwg('ebony_woods'),               bwg('jacaranda_jungle')],
    [bwg('mojave_desert'),        bwg('mojave_desert'),     bwg('windswept_desert'),         bwg('atacama_outback'),           bwg('red_rock_valley')],
]
MIDDLE_2 = [
    [bwg('shattered_glacier'),    bwg('crimson_tundra'),    bwg('frosted_coniferous_forest'), bwg('eroded_borealis'),          bwg('coniferous_forest')],
    [bwg('coconino_meadow'),      bwg('coconino_meadow'),   bwg('maple_taiga'),              bwg('dacite_ridges'),             bwg('dacite_ridges')],
    [bwg('prairie'),              bwg('orchard'),           bwg('orchard'),                  bwg('aspen_boreal'),              bwg('weeping_witch_forest')],
    [bwg('baobab_savanna'),       bwg('baobab_savanna'),    bwg('amaranth_grassland'),       bwg('crag_gardens'),              bwg('tropical_rainforest')],
    [bwg('mojave_desert'),        bwg('rugged_badlands'),   bwg('windswept_desert'),         bwg('atacama_outback'),           bwg('red_rock_valley')],
]
MIDDLE_3 = [
    [bwg('shattered_glacier'),    bwg('shattered_glacier'), bwg('frosted_coniferous_forest'), bwg('frosted_coniferous_forest'),bwg('coniferous_forest')],
    [bwg('coconino_meadow'),      bwg('coconino_meadow'),   bwg('maple_taiga'),              bwg('zelkova_forest'),            bwg('zelkova_forest')],
    [bwg('prairie'),              bwg('prairie'),           bwg('allium_shrubland'),         bwg('aspen_boreal'),              bwg('weeping_witch_forest')],
    [bwg('firecracker_chaparral'),bwg('ironwood_gour'),     bwg('amaranth_grassland'),       bwg('enchanted_tangle'),          bwg('fragment_jungle')],
    [bwg('mojave_desert'),        bwg('rugged_badlands'),   bwg('windswept_desert'),         bwg('atacama_outback'),           bwg('red_rock_valley')],
]
MIDDLE_VARIANT_BWG = [
    [DEFERRED]*5,
    [DEFERRED, DEFERRED, bwg('forgotten_forest'), DEFERRED, DEFERRED],
    [DEFERRED, DEFERRED, bwg('forgotten_forest'), DEFERRED, DEFERRED],
    [DEFERRED, DEFERRED, DEFERRED, bwg('crag_gardens'), bwg('crag_gardens')],
    [DEFERRED]*5,
]

# ── Plateau biomes ────────────────────────────────────────────────────────────

PLATEAU_1 = [
    [bwg('shattered_glacier'),    bwg('shattered_glacier'), bwg('frosted_coniferous_forest'), bwg('frosted_taiga'),    bwg('coniferous_forest')],
    [bwg('coconino_meadow'),      bwg('coconino_meadow'),   bwg('aspen_boreal'),             bwg('skyris_vale'),      bwg('skyris_vale')],
    [bwg('temperate_grove'),      bwg('temperate_grove'),   bwg('temperate_grove'),           bwg('rose_fields'),      bwg('dacite_ridges')],
    [bwg('baobab_savanna'),       bwg('baobab_savanna'),    bwg('redwood_thicket'),           bwg('jacaranda_jungle'), bwg('crag_gardens')],
    [bwg('sierra_badlands'),      bwg('sierra_badlands'),   bwg('sierra_badlands'),           bwg('red_rock_valley'),  bwg('red_rock_valley')],
]
PLATEAU_2 = [
    [bwg('shattered_glacier'),    bwg('shattered_glacier'), bwg('frosted_coniferous_forest'), bwg('frosted_taiga'),    bwg('coniferous_forest')],
    [bwg('coconino_meadow'),      bwg('coconino_meadow'),   bwg('black_forest'),             bwg('black_forest'),     bwg('black_forest')],
    [bwg('sakura_grove'),         bwg('sakura_grove'),      bwg('sakura_grove'),              bwg('rose_fields'),      bwg('weeping_witch_forest')],
    [bwg('araucaria_savanna'),    bwg('araucaria_savanna'), bwg('redwood_thicket'),           bwg('ebony_woods'),      bwg('tropical_rainforest')],
    [bwg('sierra_badlands'),      bwg('sierra_badlands'),   bwg('sierra_badlands'),           bwg('red_rock_valley'),  bwg('red_rock_valley')],
]
PLATEAU_3 = [
    [bwg('shattered_glacier'),    bwg('shattered_glacier'), bwg('frosted_coniferous_forest'), bwg('frosted_taiga'),    bwg('coniferous_forest')],
    [bwg('coconino_meadow'),      bwg('coconino_meadow'),   bwg('cika_woods'),               bwg('cika_woods'),       bwg('cika_woods')],
    [bwg('temperate_grove'),      bwg('temperate_grove'),   bwg('temperate_grove'),           bwg('rose_fields'),      bwg('weeping_witch_forest')],
    [bwg('firecracker_chaparral'),bwg('firecracker_chaparral'),bwg('redwood_thicket'),        bwg('ebony_woods'),      bwg('enchanted_tangle')],
    [bwg('sierra_badlands'),      bwg('sierra_badlands'),   bwg('sierra_badlands'),           bwg('red_rock_valley'),  bwg('red_rock_valley')],
]
PLATEAU_VARIANT_BWG = [
    [DEFERRED]*5,
    [bwg('sakura_grove'), bwg('overgrowth_woodlands'), bwg('overgrowth_woodlands'), DEFERRED, DEFERRED],
    [bwg('sakura_grove'), bwg('sakura_grove'), DEFERRED, DEFERRED, DEFERRED],
    [DEFERRED, DEFERRED, DEFERRED, DEFERRED, bwg('crag_gardens')],
    [DEFERRED]*5,
]

# ── Peak biomes ───────────────────────────────────────────────────────────────

PEAK_BWG = [
    [DEFERRED, bwg('howling_peaks'), bwg('eroded_borealis'), bwg('eroded_borealis'), bwg('eroded_borealis')],
    [DEFERRED, bwg('howling_peaks'), bwg('eroded_borealis'), bwg('eroded_borealis'), bwg('eroded_borealis')],
    [DEFERRED, bwg('howling_peaks'), bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('howling_peaks')],
    [DEFERRED, DEFERRED,             bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('crag_gardens')],
    [bwg('sierra_badlands'), bwg('sierra_badlands'), bwg('sierra_badlands'), bwg('red_rock_peaks'), bwg('red_rock_peaks')],
]
PEAK_VARIANT_BWG = [
    [DEFERRED, bwg('howling_peaks'), bwg('eroded_borealis'), bwg('eroded_borealis'), bwg('howling_peaks')],
    [DEFERRED, bwg('howling_peaks'), bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('howling_peaks')],
    [DEFERRED, bwg('howling_peaks'), bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('howling_peaks')],
    [DEFERRED, bwg('howling_peaks'), bwg('howling_peaks'),   bwg('howling_peaks'),   bwg('crag_gardens')],
    [bwg('sierra_badlands'), bwg('sierra_badlands'), bwg('sierra_badlands'), bwg('red_rock_peaks'), bwg('red_rock_peaks')],
]

# ── Slope biomes ──────────────────────────────────────────────────────────────

SLOPE_BWG = [
    [bwg('shattered_glacier'), bwg('eroded_borealis'), bwg('eroded_borealis'),  bwg('frosted_taiga'),             bwg('frosted_taiga')],
    [bwg('shattered_glacier'), bwg('eroded_borealis'), bwg('canadian_shield'),  bwg('coniferous_forest'),         bwg('frosted_coniferous_forest')],
    [bwg('shattered_glacier'), bwg('howling_peaks'),   bwg('canadian_shield'),  bwg('coniferous_forest'),         bwg('frosted_coniferous_forest')],
    [bwg('sierra_badlands'),   bwg('sierra_badlands'), bwg('redwood_thicket'),  bwg('redwood_thicket'),           bwg('crag_gardens')],
    [bwg('sierra_badlands'),   bwg('sierra_badlands'), bwg('sierra_badlands'),  bwg('red_rock_peaks'),            bwg('red_rock_peaks')],
]

# ── Shattered biomes ──────────────────────────────────────────────────────────

SHATTERED_BWG = [
    [DEFERRED]*5, [DEFERRED]*5, [DEFERRED]*5,  # temp 0-2: DEFERRED (vanilla)
    [None]*5,     [None]*5,                     # temp 3-4: THE_VOID (remove/vanilla)
]

# ── Beach biomes ──────────────────────────────────────────────────────────────

BEACH_BWG = [
    [DEFERRED]*5,
    [mc('beach'), mc('beach'), mc('beach'), bwg('dacite_shore'), bwg('dacite_shore')],
    [bwg('basalt_barrera')]*5,
    [bwg('rainbow_beach')]*5,
    [DEFERRED]*5,
]
