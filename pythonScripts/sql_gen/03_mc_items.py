"""
sql_gen/03_mc_items.py
=======================
Generates SQL inserts for the Item and Block tables — vanilla Minecraft 1.21.1.

Sources : lists/items.txt, lists/blocks.txt, lists/recipes.txt
Filter  : minecraft: namespace only

Logic:
  - Every minecraft: entry from items.txt  -> Item row
  - Every minecraft: entry that also appears in blocks.txt -> Block row
    (Block is a subtype of Item; the Item row must exist first)
  - Every unique minecraft: station from recipes.txt -> Station row
    (Station is a subtype of Item; Item row must exist first)

Fields left NULL (not available from registry dumps):
  Item  : (none — rarity_id and stackable from /dump_item_extra)
  Block : blastResistance, hardness, luminous, transparent, flammable

Output: BDD/Scripts/inserts/03_mc_items.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/03_mc_items.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_registry, load_pipe_file

OUTPUT = SQL_INSERTS / '03_mc_items.sql'


def _load_vanilla_items() -> tuple[list[str], list[str], set[str]]:
    all_items  = load_registry(LISTS, 'items.txt')
    all_blocks = load_registry(LISTS, 'blocks.txt')
    mc_items   = sorted(e for e in all_items  if e.startswith('minecraft:'))
    mc_blocks  = sorted(e for e in all_blocks if e.startswith('minecraft:'))
    print(f'Vanilla items   : {len(mc_items)}')
    print(f'Vanilla blocks  : {len(mc_blocks)}')
    print(f'  blocks-only (no item form): {len(set(mc_blocks) - set(mc_items))}')
    return mc_items, mc_blocks, set(mc_items)


def _load_vanilla_stations(mc_items_set: set[str]) -> set[str]:
    stations: set[str] = set()
    for line in load_pipe_file(LISTS, 'recipes.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 3:
            continue
        station = parts[2]
        if station.startswith('minecraft:') and station in mc_items_set:
            stations.add(station)
    print(f'Vanilla stations: {len(stations)}')
    return stations


_RARITY_IDS  = {'COMMON': 1, 'UNCOMMON': 2, 'RARE': 3, 'EPIC': 4}
_NULL_VALUES = ('null', 'None', '')


def _parse_int_or_none(value: str) -> int | None:
    return int(value) if value not in _NULL_VALUES else None


def _parse_float_or_none(value: str) -> float | None:
    return float(value) if value not in _NULL_VALUES else None


def _parse_str_or_none(value: str) -> str | None:
    return value if value not in _NULL_VALUES else None


def _parse_bool_or_none(value: str) -> bool | None:
    if value in _NULL_VALUES:
        return None
    return value.lower() == 'true'


def _load_item_extra() -> dict[str, dict]:
    """Parse item_extra.txt into {item_id: {key: value}} from /dump_item_extra."""
    result: dict[str, dict] = {}
    for line in load_pipe_file(LISTS, 'item_extra.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 5:
            continue
        result[parts[0]] = {
            'maxStackSize':     _parse_int_or_none(parts[1]),
            'enchantability':   _parse_int_or_none(parts[2]),
            'miningEfficiency': _parse_float_or_none(parts[3]),
            'miningTier':       _parse_str_or_none(parts[4]),
            'rarity':           _parse_str_or_none(parts[5]) if len(parts) >= 6 else None,
        }
    print(f'item_extra      : {len(result)} entries')
    return result


def _load_block_stats() -> dict[str, dict]:
    """Parse block_stats.txt into {block_id: {key: value}} from /dump_block_stats."""
    result: dict[str, dict] = {}
    for line in load_pipe_file(LISTS, 'block_stats.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 6:
            continue
        result[parts[0]] = {
            'blastResistance': _parse_float_or_none(parts[1]),
            'hardness':        _parse_float_or_none(parts[2]),
            'luminous':        _parse_bool_or_none(parts[3]),
            'transparent':     _parse_bool_or_none(parts[4]),
            'flammable':       _parse_bool_or_none(parts[5]),
        }
    print(f'block_stats     : {len(result)} entries')
    return result


def _build_item_rows(mc_items: list[str], item_extra: dict) -> list[tuple]:
    rows = []
    for item_id in mc_items:
        name = item_id.split(':', 1)[1]
        extra     = item_extra.get(item_id, {})
        max_stack = extra.get('maxStackSize')
        stackable = (True if max_stack > 1 else False) if max_stack is not None else None
        rarity    = extra.get('rarity')
        rarity_id = _RARITY_IDS.get(rarity) if rarity else None
        rows.append(('minecraft', name, rarity_id, stackable))
    return rows


def _build_block_rows(mc_blocks: list[str], mc_items_set: set[str], block_stats: dict) -> list[tuple]:
    rows = []
    for block_id in mc_blocks:
        if block_id not in mc_items_set:
            continue
        name = block_id.split(':', 1)[1]
        s = block_stats.get(block_id, {})
        rows.append(('minecraft', name,
                     s.get('blastResistance'),
                     s.get('hardness'),
                     s.get('luminous'),
                     s.get('transparent'),
                     s.get('flammable')))
    return rows


def _build_station_rows(mc_stations: set[str]) -> list[tuple]:
    return [
        ('minecraft', name.split(':', 1)[1])
        for name in sorted(mc_stations)
    ]


def main() -> None:
    mc_items, mc_blocks, mc_items_set = _load_vanilla_items()
    mc_stations = _load_vanilla_stations(mc_items_set)
    item_extra  = _load_item_extra()
    block_stats = _load_block_stats()

    write_sql(OUTPUT, '03 - Minecraft items, blocks and stations', [
        ('Item — all vanilla items  (rarity_id and stackable from /dump_item_extra)',
         insert_rows(
             'Item',
             ['namespace', 'name', 'rarity_id', 'stackable'],
             _build_item_rows(mc_items, item_extra),
         )),

        ('Block — vanilla blocks that have an item form  (physical properties from /dump_block_stats)',
         insert_rows(
             'Block',
             ['namespace', 'name', 'blastResistance', 'hardness', 'luminous', 'transparent', 'flammable'],
             _build_block_rows(mc_blocks, mc_items_set, block_stats),
         )),

        ('Station — vanilla crafting stations derived from the station column of recipes.txt',
         insert_rows(
             'Station',
             ['item_namespace', 'item_name'],
             _build_station_rows(mc_stations),
         )),
    ])


main()
