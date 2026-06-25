"""
sql_gen/18_mc_loot_block.py
============================
Generates SQL inserts for LootTableBlock — vanilla Minecraft 1.21.1.

Maps each block loot table path to its corresponding Block row.
Block loot paths follow the pattern minecraft:blocks/<block_name>.

Sources
-------
  lists/loot_blocks.txt  — block drop loot tables (same source as 06_mc_loot.py)
  lists/loot_entities.txt, loot_chests.txt, loot_other.txt, loot_references.txt
                         — needed to replicate the loot_id_map from 06_mc_loot.py
  lists/blocks.txt       — valid block registry
  lists/items.txt        — valid item registry (Block FKs to Item since schema fix)

Filter: only minecraft: block loot paths whose block name exists in both
        blocks.txt AND items.txt (Block table only holds blocks with item forms).

IMPORTANT: loot ID assignment must stay in sync with 06_mc_loot.py.
Both scripts sort all minecraft: loot paths alphabetically and assign
sequential IDs starting at 1. Any change to LOOT_FILES or the loot
parsing logic in 06 must be mirrored here.

Output: BDD/Scripts/inserts/18_mc_loot_block.sql
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths   import LISTS, SQL_INSERTS
from lib.loaders import load_pipe_file, load_registry

OUTPUT      = SQL_INSERTS / '18_mc_loot_block.sql'
# GOTCHA: must be identical to LOOT_FILES in 06_mc_loot.py.
# IDs are assigned by sorting the union of all paths from these files alphabetically.
# Adding/removing a file in 06 shifts IDs → LootTableBlock rows reference wrong LootTable rows.
LOOT_FILES  = ['loot_entities.txt', 'loot_chests.txt', 'loot_blocks.txt', 'loot_other.txt']
BLOCK_PREFIX = 'minecraft:blocks/'


def _build_loot_id_map() -> dict[str, int]:
    loot_paths: set[str] = set()
    for filename in LOOT_FILES:
        for line in load_pipe_file(LISTS, filename):
            source = [p.strip() for p in line.split('|')][0]
            if source.startswith('minecraft:'):
                loot_paths.add(source)
    for line in load_pipe_file(LISTS, 'loot_references.txt'):
        for part in [p.strip() for p in line.split('|')]:
            if part.startswith('minecraft:'):
                loot_paths.add(part)
    return {path: idx + 1 for idx, path in enumerate(sorted(loot_paths))}


def _build_loot_block_rows(loot_id_map: dict) -> list[tuple]:
    mc_blocks     = {b for b in load_registry(LISTS, 'blocks.txt') if b.startswith('minecraft:')}
    mc_items      = {i for i in load_registry(LISTS, 'items.txt')  if i.startswith('minecraft:')}
    valid_blocks  = mc_blocks & mc_items

    rows: list[tuple] = []
    seen: set[tuple]  = set()
    for loot_path, loot_id in sorted(loot_id_map.items(), key=lambda x: x[1]):
        if not loot_path.startswith(BLOCK_PREFIX):
            continue
        block_name = loot_path[len(BLOCK_PREFIX):]
        if f'minecraft:{block_name}' not in valid_blocks:
            continue
        pk = (loot_id, 'minecraft', block_name)
        if pk not in seen:
            seen.add(pk)
            rows.append(pk)
    print(f'LootTableBlock entries: {len(rows)}')
    return rows


def main() -> None:
    loot_id_map = _build_loot_id_map()
    rows        = _build_loot_block_rows(loot_id_map)
    write_sql(OUTPUT, '18 - Block loot table junction (LootTableBlock)', [
        ('LootTableBlock — maps each block loot table to its Block row',
         insert_rows(
             'LootTableBlock',
             ['loottable_id', 'block_namespace', 'block_name'],
             rows,
         )),
    ])


main()
