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

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths       import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils    import write_txt
from lib.jar_scanner import scan_jars

from collectors.recipes  import make_recipe_collector
from collectors.loot     import make_loot_collector
from collectors.world    import make_biome_collector, make_structure_collector
from collectors.mod_meta import make_mod_meta_collector
from collectors.lang     import make_lang_collector, lang_rows_from_dict


def _build_handlers() -> tuple[dict, dict]:
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

    biome_stat_rows  = []
    entity_cat_dict  = {}   # entity_id -> primary spawn category

    collect_biome = make_biome_collector(biome_spawn_rows, biome_feature_rows, biome_stat_rows, entity_cat_dict)
    collect_structure, collect_structure_set = make_structure_collector(structure_biome_rows, structure_spawn_rows)

    handlers = {
        '__mod_meta__':            make_mod_meta_collector(mod_meta_rows),
        '__lang__':                make_lang_collector(lang_dict),
        r'recipes?/':              make_recipe_collector(recipe_rows),
        r'loot_tables?/':          make_loot_collector(
                                       loot_entity_rows, loot_chest_rows,
                                       loot_block_rows, loot_other_rows,
                                       loot_reference_rows,
                                   ),
        'worldgen/biome/':         collect_biome,
        'worldgen/structure/':     collect_structure,
        'worldgen/structure_set/': collect_structure_set,
    }

    buckets = {
        'recipe_rows':          recipe_rows,
        'loot_entity_rows':     loot_entity_rows,
        'loot_chest_rows':      loot_chest_rows,
        'loot_block_rows':      loot_block_rows,
        'loot_other_rows':      loot_other_rows,
        'loot_reference_rows':  loot_reference_rows,
        'biome_spawn_rows':     biome_spawn_rows,
        'biome_feature_rows':   biome_feature_rows,
        'biome_stat_rows':      biome_stat_rows,
        'entity_cat_dict':      entity_cat_dict,
        'structure_biome_rows': structure_biome_rows,
        'structure_spawn_rows': structure_spawn_rows,
        'mod_meta_rows':        mod_meta_rows,
        'lang_dict':            lang_dict,
    }

    return handlers, buckets


def _write_output_files(buckets: dict) -> None:
    print('\nWriting output files ...')

    write_txt(LISTS, 'recipes.txt', [(
        'RECIPES  format: id | type | station | result | count | ingredients',
        buckets['recipe_rows'],
    )])
    write_txt(LISTS, 'loot_entities.txt', [(
        'LOOT -- ENTITIES  format: source | item | min | max | weight',
        buckets['loot_entity_rows'],
    )])
    write_txt(LISTS, 'loot_chests.txt', [(
        'LOOT -- CHESTS  format: source | item | min | max | weight',
        buckets['loot_chest_rows'],
    )])
    write_txt(LISTS, 'loot_blocks.txt', [(
        'LOOT -- BLOCKS  format: source | item | min | max | weight',
        buckets['loot_block_rows'],
    )])
    if buckets['loot_other_rows']:
        write_txt(LISTS, 'loot_other.txt', [(
            'LOOT -- OTHER  format: source | item | min | max | weight',
            buckets['loot_other_rows'],
        )])
    write_txt(LISTS, 'loot_references.txt', [(
        'LOOT TABLE REFERENCES  format: parent_source | child_source',
        buckets['loot_reference_rows'],
    )])
    write_txt(LISTS, 'biome_spawns.txt', [(
        'BIOME SPAWN TABLE  format: biome | entity | weight | min | max',
        buckets['biome_spawn_rows'],
    )])
    write_txt(LISTS, 'biome_features.txt', [(
        'BIOME FEATURES  format: biome | feature_id',
        buckets['biome_feature_rows'],
    )])
    write_txt(LISTS, 'biome_stats.txt', [(
        'BIOME STATS  format: biome_id | temperature | mainColor (#RRGGBB fog)',
        buckets['biome_stat_rows'],
    )])
    entity_behavior_rows = [
        f"{eid} | {cat}"
        for eid, cat in sorted(buckets['entity_cat_dict'].items())
    ]
    write_txt(LISTS, 'entity_behavior.txt', [(
        'ENTITY BEHAVIOR  format: entity_id | spawn_category',
        entity_behavior_rows,
    )])
    write_txt(LISTS, 'structure_biomes.txt', [(
        'STRUCTURE -> BIOME  format: structure | biome_or_tag',
        buckets['structure_biome_rows'],
    )])
    write_txt(LISTS, 'structure_spawns.txt', [(
        'STRUCTURE SPAWN OVERRIDES  format: structure | entity | weight | min | max',
        buckets['structure_spawn_rows'],
    )])
    write_txt(LISTS, 'mod_meta.txt', [(
        'MOD METADATA  format: mod_id | display_name | version | license | authors | url',
        buckets['mod_meta_rows'],
    )])
    write_txt(LISTS, 'lang_names.txt', [(
        'LANG DISPLAY NAMES  format: lang_key | display_name',
        lang_rows_from_dict(buckets['lang_dict']),
    )])

    print(f'\nDone. All files written to {LISTS}')


def main() -> None:
    handlers, buckets = _build_handlers()
    scan_jars(MODS_DIR, handlers, extra_jars=[VANILLA_JAR])
    _write_output_files(buckets)


main()
