"""
sql_gen/06_mc_loot.py
======================
Generates SQL inserts for the loot system — vanilla Minecraft 1.21.1.

Sources
-------
  lists/loot_entities.txt   — entity drop loot tables
  lists/loot_chests.txt     — chest/structure loot tables
  lists/loot_blocks.txt     — block drop loot tables
  lists/loot_other.txt      — archaeology, advancements, etc.
  lists/loot_references.txt — parent -> child loot table references
  lists/entity_loottables.txt — entity -> loot table path mapping

Filter: only minecraft: loot table sources AND minecraft: items
  (non-vanilla items are mod injections; their Item rows don't exist yet)

Outputs
-------
  LootTable          — one row per unique minecraft: loot table path (id assigned here)
  Comprises          — (loottable_id, item, min, max, weight)
                       deduplicated per (loottable_id, item): last row wins
  LootTableEntity    — junction: which entity is linked to which loot table
  LootTableReference — parent/child loot table nesting

Note: LootTableStructure is handled in a later step (requires NBT-derived mapping).

Output: BDD/Scripts/inserts/06_mc_loot.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/06_mc_loot.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_pipe_file

OUTPUT = SQL_INSERTS / '06_mc_loot.sql'

# GOTCHA: 18_mc_loot_block.py duplicates this list to rebuild the same loot_id_map.
# If you add/remove a file here, mirror the change there or LootTableBlock IDs will be wrong.
LOOT_FILES = ['loot_entities.txt', 'loot_chests.txt', 'loot_blocks.txt', 'loot_other.txt']


def _parse_pipe_row(raw_line: str) -> list[str]:
    return [p.strip() for p in raw_line.split('|')]


def _parse_int_field(field: str) -> int:
    return int(field.split(':', 1)[1]) if ':' in field else 1


def _build_loot_id_map() -> dict[str, int]:
    loot_paths: set[str] = set()
    for filename in LOOT_FILES:
        for line in load_pipe_file(LISTS, filename):
            source = _parse_pipe_row(line)[0]
            if source.startswith('minecraft:'):
                loot_paths.add(source)
    for line in load_pipe_file(LISTS, 'loot_references.txt'):
        for part in _parse_pipe_row(line):
            if part.startswith('minecraft:'):
                loot_paths.add(part)
    loot_id_map = {path: idx + 1 for idx, path in enumerate(sorted(loot_paths))}
    print(f'Unique minecraft: loot tables : {len(loot_id_map)}')
    return loot_id_map


def _build_comprises_rows(loot_id_map: dict) -> list[tuple]:
    comprises_map: dict[tuple, tuple] = {}
    for filename in LOOT_FILES:
        for line in load_pipe_file(LISTS, filename):
            parts = _parse_pipe_row(line)
            if len(parts) < 5:
                continue
            source, item = parts[0], parts[1]
            if not source.startswith('minecraft:') or source not in loot_id_map:
                continue
            if not item.startswith('minecraft:'):
                continue
            loot_id          = loot_id_map[source]
            item_ns, item_name = item.split(':', 1)
            min_val          = _parse_int_field(parts[2])
            max_val          = _parse_int_field(parts[3])
            weight           = _parse_int_field(parts[4])
            comprises_map[(loot_id, item_ns, item_name)] = (min_val, max_val, weight)
    rows = [
        (loot_id, ns, name, min_val, max_val, weight)
        for (loot_id, ns, name), (min_val, max_val, weight) in sorted(comprises_map.items())
    ]
    print(f'Comprises entries             : {len(rows)}')
    return rows


def _build_entity_loot_rows(loot_id_map: dict) -> list[tuple]:
    entity_rows: list[tuple] = []
    for line in load_pipe_file(LISTS, 'entity_loottables.txt'):
        parts = _parse_pipe_row(line)
        if len(parts) < 2:
            continue
        entity, loot_path = parts[0], parts[1]
        if not entity.startswith('minecraft:') or not loot_path.startswith('minecraft:'):
            continue
        if loot_path not in loot_id_map:
            continue
        loot_id          = loot_id_map[loot_path]
        ent_ns, ent_name = entity.split(':', 1)
        entity_rows.append((loot_id, ent_ns, ent_name))
    print(f'LootTableEntity entries       : {len(entity_rows)}')
    return entity_rows


def _build_reference_rows(loot_id_map: dict) -> list[tuple]:
    rows: list[tuple] = []
    for line in load_pipe_file(LISTS, 'loot_references.txt'):
        parts = _parse_pipe_row(line)
        if len(parts) < 2:
            continue
        parent, child = parts[0], parts[1]
        if parent in loot_id_map and child in loot_id_map:
            rows.append((loot_id_map[parent], loot_id_map[child]))
    print(f'LootTableReference entries    : {len(rows)}')
    return rows


def main() -> None:
    loot_id_map    = _build_loot_id_map()
    loottable_rows = [(loot_id_map[path],) for path in sorted(loot_id_map)]
    comprises_rows = _build_comprises_rows(loot_id_map)
    entity_rows    = _build_entity_loot_rows(loot_id_map)
    reference_rows = _build_reference_rows(loot_id_map)

    write_sql(OUTPUT, '06 - Minecraft loot tables', [
        ('LootTable — one row per unique minecraft: loot table path',
         insert_rows('LootTable', ['id'], loottable_rows)),

        ('Comprises — (loottable_id, item, min, max, weight)',
         insert_rows(
             'Comprises',
             ['loottable_id', 'item_namespace', 'item_name', 'minNumber', 'maxNumber', 'weight'],
             comprises_rows,
         )),

        ('LootTableEntity — entity to loot table junction',
         insert_rows(
             'LootTableEntity',
             ['loottable_id', 'entity_namespace', 'entity_name'],
             entity_rows,
         )),

        ('LootTableReference — nested loot table references',
         insert_rows(
             'LootTableReference',
             ['parent_id', 'child_id'],
             reference_rows,
         )),
    ])


main()
