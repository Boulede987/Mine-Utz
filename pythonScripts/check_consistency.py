"""
check_consistency.py
====================
Cross-references all extracted .txt files and reports inconsistencies.

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/check_consistency.py

Output: lists/consistency_report.txt
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from lib.paths   import LISTS
from lib.loaders import load_registry, load_pipe_file
from lib.normalise import add_minecraft_prefix

import checks.file_presence           as check_file_presence
import checks.namespace_coverage      as check_namespace_coverage
import checks.recipe_integrity        as check_recipe_integrity
import checks.loot_integrity          as check_loot_integrity
import checks.biome_spawn_integrity   as check_biome_spawn_integrity
import checks.structure_biome_integrity as check_structure_biome_integrity
import checks.lang_coverage           as check_lang_coverage

SAMPLE_LIMIT = 20


def _load_data() -> dict:
    print('Loading files...')
    items      = load_registry(LISTS, 'items.txt')
    blocks     = load_registry(LISTS, 'blocks.txt')
    mobs       = load_registry(LISTS, 'mobs.txt')
    biomes     = load_registry(LISTS, 'biomes.txt')
    structures = load_registry(LISTS, 'structures.txt')
    return {
        'items':                  items,
        'blocks':                 blocks,
        'mobs':                   mobs,
        'biomes':                 biomes,
        'structures':             structures,
        'all_items':              items | blocks,
        'recipe_lines':           load_pipe_file(LISTS, 'recipes.txt'),
        'loot_entity_sources':    load_pipe_file(LISTS, 'loot_entities.txt',    column=0),
        'loot_chest_sources':     load_pipe_file(LISTS, 'loot_chests.txt',      column=0),
        'loot_block_sources':     load_pipe_file(LISTS, 'loot_blocks.txt',      column=0),
        'loot_entity_items':      load_pipe_file(LISTS, 'loot_entities.txt',    column=1),
        'loot_chest_items':       load_pipe_file(LISTS, 'loot_chests.txt',      column=1),
        'loot_block_items':       load_pipe_file(LISTS, 'loot_blocks.txt',      column=1),
        'spawn_biomes_raw':       load_pipe_file(LISTS, 'biome_spawns.txt',     column=0),
        'spawn_entities_raw':     load_pipe_file(LISTS, 'biome_spawns.txt',     column=1),
        'struct_biome_structs':   load_pipe_file(LISTS, 'structure_biomes.txt', column=0),
        'struct_biome_biomes':    load_pipe_file(LISTS, 'structure_biomes.txt', column=1),
        'entity_loot_entities':   load_pipe_file(LISTS, 'entity_loottables.txt', column=0),
        'entity_loot_tables':     load_pipe_file(LISTS, 'entity_loottables.txt', column=1),
        'mod_ids':                load_pipe_file(LISTS, 'mod_meta.txt',          column=0),
        'lang_keys':              load_pipe_file(LISTS, 'lang_names.txt',        column=0),
    }


def _run_checks(data: dict) -> list:
    return [
        ('FILE PRESENCE',            check_file_presence.check(LISTS)),
        ('NAMESPACE COVERAGE',       check_namespace_coverage.check(
                                         data['all_items'], data['mobs'], data['biomes'], data['mod_ids'])),
        ('RECIPE INTEGRITY',         check_recipe_integrity.check(
                                         data['recipe_lines'], data['all_items'])),
        ('LOOT TABLE INTEGRITY',     check_loot_integrity.check(
                                         data['loot_entity_items'], data['loot_chest_items'],
                                         data['loot_block_items'], data['entity_loot_entities'],
                                         data['entity_loot_tables'], data['loot_entity_sources'],
                                         data['all_items'])),
        ('BIOME SPAWN INTEGRITY',    check_biome_spawn_integrity.check(
                                         data['spawn_biomes_raw'], data['spawn_entities_raw'],
                                         data['biomes'], data['mobs'])),
        ('STRUCTURE-BIOME INTEGRITY', check_structure_biome_integrity.check(
                                         data['struct_biome_structs'], data['struct_biome_biomes'],
                                         data['structures'], data['biomes'])),
        ('LANG NAME COVERAGE',       check_lang_coverage.check(
                                         data['all_items'], data['mobs'], data['lang_keys'])),
    ]


def _format_report(sections: list) -> tuple[str, int, int]:
    lines          = []
    total_warnings = sum(len(result['warnings']) for _, result in sections)
    total_info     = sum(len(result['info'])     for _, result in sections)

    lines.append('Consistency Report')
    lines.append('==================')
    lines.append(f'Warnings : {total_warnings}')
    lines.append(f'Info     : {total_info}')

    for section_title, result in sections:
        lines.append(f'\n{"="*60}')
        lines.append(f'  {section_title}')
        lines.append(f'{"="*60}')

        for entry in result['ok']:
            lines.append(f'  OK  : {entry}')

        for entry in result['warnings']:
            if isinstance(entry, tuple):
                message, items_list = entry
                lines.append(f'  WARN: {message}')
                for item in items_list[:SAMPLE_LIMIT]:
                    lines.append(f'    - {item}')
                if len(items_list) > SAMPLE_LIMIT:
                    lines.append(f'    ... and {len(items_list) - SAMPLE_LIMIT} more')
            else:
                lines.append(f'  WARN: {entry}')

        for entry in result['info']:
            if isinstance(entry, tuple):
                message, items_list = entry
                lines.append(f'  INFO: {message}')
                for item in items_list[:SAMPLE_LIMIT]:
                    lines.append(f'    - {item}')
                if len(items_list) > SAMPLE_LIMIT:
                    lines.append(f'    ... and {len(items_list) - SAMPLE_LIMIT} more')
            else:
                lines.append(f'  INFO: {entry}')

    return '\n'.join(lines), total_warnings, total_info


def _write_report(report_text: str) -> None:
    print(report_text)
    dest = LISTS / 'consistency_report.txt'
    dest.write_text(report_text, encoding='utf-8')
    print(f'\nWritten to {dest}')


def main() -> None:
    data        = _load_data()
    sections    = _run_checks(data)
    report_text, _, _ = _format_report(sections)
    _write_report(report_text)


main()
