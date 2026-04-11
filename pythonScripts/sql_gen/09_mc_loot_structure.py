"""
sql_gen/09_mc_loot_structure.py
================================
Generates SQL inserts for LootTableStructure — vanilla Minecraft 1.21.1.

Why hardcoded?
  Vanilla structure chest loot tables are assigned at world-generation time
  in Java code, not stored in NBT files. extract_structure_loottables.py
  (which reads NBT) produces 0 vanilla entries. The mapping from chest loot
  table paths to worldgen structure registry IDs must be declared explicitly.

  Entries intentionally omitted:
    - minecraft:chests/simple_dungeon   (dungeons are a feature, not a registered structure)
    - minecraft:chests/spawn_bonus_chest (world-start bonus chest, not a structure)
    - Unrecognised paths injected by mods into the minecraft: namespace

Source for loot table integer IDs: same path-sorted assignment as 06_mc_loot.py.

Output: BDD/Scripts/inserts/09_mc_loot_structure.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/09_mc_loot_structure.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, ROOT
from lib.loaders import load_pipe_file

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '09_mc_loot_structure.sql'

LOOT_FILES = ['loot_entities.txt', 'loot_chests.txt', 'loot_blocks.txt', 'loot_other.txt']


# ── Rebuild loot_id_map (must match 06_mc_loot.py exactly) ───────────────────

loot_paths: set[str] = set()

for filename in LOOT_FILES:
    for line in load_pipe_file(LISTS, filename):
        source = line.split('|')[0].strip()
        if source.startswith('minecraft:'):
            loot_paths.add(source)

for line in load_pipe_file(LISTS, 'loot_references.txt'):
    for part in line.split('|'):
        p = part.strip()
        if p.startswith('minecraft:'):
            loot_paths.add(p)

loot_id_map: dict[str, int] = {
    path: idx + 1
    for idx, path in enumerate(sorted(loot_paths))
}

# ── Hardcoded loot table path → structure name(s) mapping ────────────────────
# Keys are exact chest loot table paths (minecraft: namespace).
# Values are lists of structure names (namespace implied as 'minecraft').
# Prefix rules (illager_mansion/*, village/*, trial_chambers/*, etc.) are
# handled separately below.

EXACT: dict[str, list[str]] = {
    'minecraft:chests/abandoned_mineshaft':     ['mineshaft', 'mineshaft_mesa'],
    'minecraft:chests/ancient_city':            ['ancient_city'],
    'minecraft:chests/ancient_city_ice_box':    ['ancient_city'],
    'minecraft:chests/bastion_bridge':          ['bastion_remnant'],
    'minecraft:chests/bastion_hoglin_stable':   ['bastion_remnant'],
    'minecraft:chests/bastion_other':           ['bastion_remnant'],
    'minecraft:chests/bastion_treasure':        ['bastion_remnant'],
    'minecraft:chests/buried_treasure':         ['buried_treasure'],
    'minecraft:chests/desert_pyramid':          ['desert_pyramid'],
    'minecraft:chests/end_city_treasure':       ['end_city'],
    'minecraft:chests/igloo_chest':             ['igloo'],
    'minecraft:chests/jungle_temple':           ['jungle_pyramid'],
    'minecraft:chests/jungle_temple_dispenser': ['jungle_pyramid'],
    'minecraft:chests/nether_bridge':           ['fortress'],
    'minecraft:chests/pillager_outpost':        ['pillager_outpost'],
    'minecraft:chests/ruined_portal':           [
        'ruined_portal', 'ruined_portal_desert', 'ruined_portal_jungle',
        'ruined_portal_mountain', 'ruined_portal_nether',
        'ruined_portal_ocean', 'ruined_portal_swamp',
    ],
    'minecraft:chests/shipwreck_map':           ['shipwreck', 'shipwreck_beached'],
    'minecraft:chests/shipwreck_supply':        ['shipwreck', 'shipwreck_beached'],
    'minecraft:chests/shipwreck_treasure':      ['shipwreck', 'shipwreck_beached'],
    'minecraft:chests/stronghold_corridor':     ['stronghold'],
    'minecraft:chests/stronghold_crossing':     ['stronghold'],
    'minecraft:chests/stronghold_library':      ['stronghold'],
    'minecraft:chests/underwater_ruin_big':     ['ocean_ruin_cold', 'ocean_ruin_warm'],
    'minecraft:chests/underwater_ruin_small':   ['ocean_ruin_cold', 'ocean_ruin_warm'],
    'minecraft:chests/woodland_mansion':        ['mansion'],

    # Village profession chests appear in every village type
    'minecraft:chests/village/village_armorer':      ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_butcher':      ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_cartographer': ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_fisher':       ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_fletcher':     ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_mason':        ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_shepherd':     ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_tannery':      ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_temple':       ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_toolsmith':    ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],
    'minecraft:chests/village/village_weaponsmith':  ['village_desert', 'village_plains', 'village_savanna', 'village_snowy', 'village_taiga'],

    # Village house chests are biome-specific
    'minecraft:chests/village/village_desert_house':  ['village_desert'],
    'minecraft:chests/village/village_plains_house':  ['village_plains'],
    'minecraft:chests/village/village_savanna_house': ['village_savanna'],
    'minecraft:chests/village/village_snowy_house':   ['village_snowy'],
    'minecraft:chests/village/village_taiga_house':   ['village_taiga'],
    # jungle/swamp house loot tables exist but have no matching registered structure
}

# Prefix rules: any loot table path starting with the key maps to the listed structures
PREFIXES: list[tuple[str, list[str]]] = [
    ('minecraft:chests/illager_mansion/',        ['mansion']),
    ('minecraft:chests/nether_fortress/',        ['fortress']),
    ('minecraft:chests/trial_chambers/',         ['trial_chambers']),
    ('minecraft:dispensers/trial_chambers/',     ['trial_chambers']),
    ('minecraft:pots/trial_chambers/',           ['trial_chambers']),
    ('minecraft:spawners/trial_chamber/',        ['trial_chambers']),
    ('minecraft:spawners/ominous/trial_chamber/', ['trial_chambers']),
]


def structures_for(path: str) -> list[str]:
    """Return the list of structure names for a given loot table path."""
    if path in EXACT:
        return EXACT[path]
    for prefix, structures in PREFIXES:
        if path.startswith(prefix):
            return structures
    return []


# ── Build rows ────────────────────────────────────────────────────────────────

rows: list[tuple] = []
seen: set[tuple] = set()
skipped_no_map   = []
skipped_no_id    = []

for path, loot_id in sorted(loot_id_map.items()):
    structures = structures_for(path)
    if not structures:
        skipped_no_map.append(path)
        continue
    for struct_name in structures:
        pk = (loot_id, 'minecraft', struct_name)
        if pk not in seen:
            seen.add(pk)
            rows.append(pk)

print(f'LootTableStructure rows : {len(rows)}')
print(f'Skipped (no mapping)    : {len(skipped_no_map)}')
if skipped_no_map:
    for p in sorted(skipped_no_map):
        print(f'  {p}')

# ── Write ─────────────────────────────────────────────────────────────────────

write_sql(OUTPUT, '09 - Minecraft LootTableStructure', [
    ('LootTableStructure — chest loot tables linked to their structures',
     insert_rows(
         'LootTableStructure',
         ['loottable_id', 'structure_namespace', 'structure_name'],
         rows,
     )),
])
