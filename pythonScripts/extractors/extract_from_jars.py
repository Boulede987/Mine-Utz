"""
extract_from_jars.py
====================
Scans all mod jars and writes extracted data to lists/.

Output files
------------
  recipes.txt
  loot_entities.txt
  loot_chests.txt
  loot_blocks.txt
  loot_other.txt
  loot_references.txt
  biome_spawns.txt
  biome_features.txt
  structure_biomes.txt
  mod_meta.txt
  lang_names.txt

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_from_jars.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from lib.paths       import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils    import write_txt
from lib.jar_scanner import scan_jars

from collectors.recipes  import make_recipe_collector
from collectors.loot     import make_loot_collector
from collectors.world    import make_biome_collector, make_structure_collector
from collectors.mod_meta import make_mod_meta_collector
from collectors.lang     import make_lang_collector, lang_rows_from_dict

# ── Output buckets ────────────────────────────────────────────────────────────

recipe_rows          = []
loot_entity_rows     = []
loot_chest_rows      = []
loot_block_rows      = []
loot_other_rows      = []
loot_reference_rows  = []
biome_spawn_rows     = []
biome_feature_rows   = []
structure_biome_rows = []
structure_spawn_rows = []
mod_meta_rows        = []
lang_dict            = {}

# ── Build collectors ──────────────────────────────────────────────────────────

collect_recipe = make_recipe_collector(recipe_rows)
collect_loot   = make_loot_collector(
    loot_entity_rows,
    loot_chest_rows,
    loot_block_rows,
    loot_other_rows,
    loot_reference_rows,
)
collect_biome, collect_structure_set = make_biome_collector(
    biome_spawn_rows, biome_feature_rows
), make_structure_collector(structure_biome_rows)[1]

collect_structure, collect_structure_set = make_structure_collector(structure_biome_rows, structure_spawn_rows)
collect_mod_meta = make_mod_meta_collector(mod_meta_rows)
collect_lang     = make_lang_collector(lang_dict)

# ── Scan ──────────────────────────────────────────────────────────────────────

scan_jars(MODS_DIR, {
    '__mod_meta__':           collect_mod_meta,
    '__lang__':               collect_lang,
    r'recipes?/':             collect_recipe,
    r'loot_tables?/':         collect_loot,
    'worldgen/biome/':        collect_biome,
    'worldgen/structure/':    collect_structure,
    'worldgen/structure_set/': collect_structure_set,
}, extra_jars=[VANILLA_JAR])

# ── Write outputs ─────────────────────────────────────────────────────────────

print('\nWriting output files ...')

write_txt(LISTS, 'recipes.txt', [(
    'RECIPES  format: id | type | station | result | count | ingredients',
    recipe_rows,
)])
write_txt(LISTS, 'loot_entities.txt', [(
    'LOOT -- ENTITIES  format: source | item | min | max | weight',
    loot_entity_rows,
)])
write_txt(LISTS, 'loot_chests.txt', [(
    'LOOT -- CHESTS  format: source | item | min | max | weight',
    loot_chest_rows,
)])
write_txt(LISTS, 'loot_blocks.txt', [(
    'LOOT -- BLOCKS  format: source | item | min | max | weight',
    loot_block_rows,
)])
if loot_other_rows:
    write_txt(LISTS, 'loot_other.txt', [(
        'LOOT -- OTHER  format: source | item | min | max | weight',
        loot_other_rows,
    )])
write_txt(LISTS, 'loot_references.txt', [(
    'LOOT TABLE REFERENCES  format: parent_source | child_source',
    loot_reference_rows,
)])
write_txt(LISTS, 'biome_spawns.txt', [(
    'BIOME SPAWN TABLE  format: biome | entity | weight | min | max',
    biome_spawn_rows,
)])
write_txt(LISTS, 'biome_features.txt', [(
    'BIOME FEATURES  format: biome | feature_id',
    biome_feature_rows,
)])
write_txt(LISTS, 'structure_biomes.txt', [(
    'STRUCTURE -> BIOME  format: structure | biome_or_tag',
    structure_biome_rows,
)])
write_txt(LISTS, 'structure_spawns.txt', [(
    'STRUCTURE SPAWN OVERRIDES  format: structure | entity | weight | min | max',
    structure_spawn_rows,
)])
write_txt(LISTS, 'mod_meta.txt', [(
    'MOD METADATA  format: mod_id | display_name | version | license | authors | url',
    mod_meta_rows,
)])
write_txt(LISTS, 'lang_names.txt', [(
    'LANG DISPLAY NAMES  format: lang_key | display_name',
    lang_rows_from_dict(lang_dict),
)])

print(f'\nDone. All files written to {LISTS}')
