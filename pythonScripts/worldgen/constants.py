from pathlib import Path

CHECK_MARK = '✓'
CROSS_MARK = '✗'

BIOME_PLANNER_FIXED_COLUMN_COUNT     = 4
STRUCTURE_PLANNER_FIXED_COLUMN_COUNT = 2

BIOME_PLANNER_FILE     = Path('dimension_biome_planner.xlsx')
BIOME_PLANNER_SHEET    = 'Biome Planner'
STRUCTURE_PLANNER_FILE = Path('structure_planner.xlsx')

OVERWORLD_PLANNER_HEADER = 'Overworld'

ALL_SECTIONS = ['surface', 'underground', 'caves', 'deep']
END_SECTIONS = ['highlands', 'midlands', 'edge', 'islands']

NAMESPACE_TO_SOURCE = {
    'minecraft':                      'Vanilla',
    'biomesoplenty':                  'BoP',
    'biomeswevegone':                 'BWG',
    'shineals_prehistoric_expansion': 'Shineals',
    'undergroundworlds':              'Underground Worlds',
    'repurposed_structures':          'Repurposed Structs',
    'dungeons_arise':                 'Dungeons Arise',
    'towns_and_towers':               'Towns & Towers',
    'structory':                      'Structory',
    'structory_towers':               'Structory Towers',
    'bettermineshafts':               'Better Mineshafts',
    'betterstrongholds':              'Better Strongholds',
    'betterfortresses':               'Better Fortresses',
    'betterdungeons':                 'Better Dungeons',
    'betterdeserttemples':            'Better Desert Temples',
    'betteroceanmonuments':           'Better Ocean Monuments',
    'mostructures':                   'Mo Structures',
    'lostcities':                     'Lost Cities',
    'aether':                         'Aether',
    'twilightforest':                 'Twilight Forest',
    'deeperdarker':                   'Deeper & Darker',
    'apotheosis':                     'Apotheosis',
    'irons_spellbooks':               "Iron's Spellbooks",
    'iceandfire':                     'Ice & Fire',
    'cataclysm':                      'Cataclysm',
    'cobblemon':                      'Cobblemon',
}
