"""
sql_gen/15_mc_subtypes.py
==========================
Generates SQL inserts for Tool, Armor, Food tables — vanilla Minecraft 1.21.1.

Sources
-------
  lists/item_tags.txt   — classification (which items are Tool/Armor/Food)
  lists/items.txt       — FK validation
  lists/tool_stats.txt  — attackDamage, attackSpeed, durability  (from /dump_tool_stats)
  lists/armor_stats.txt — armorPoints, armorToughness, knockbackResistance, durability
  lists/food_stats.txt  — hungerRestoration, saturation          (from /dump_food_stats)
  lists/item_extra.txt  — enchantability, miningEfficiency, miningTier (from /dump_item_extra)

All stats files are optional: if absent, stat columns are NULL.

Output: BDD/Scripts/inserts/15_mc_subtypes.sql
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils    import insert_rows, write_sql
from lib.paths        import LISTS, SQL_INSERTS
from lib.loaders      import load_registry, load_pipe_file
from lib.tag_resolver import load_tag_map, resolve_tag

OUTPUT = SQL_INSERTS / '15_mc_subtypes.sql'

# Tag names as of 1.21.1 (vanilla renamed armor tags; food split into meat/fish/etc.)
TOOL_TAGS  = ['minecraft:swords', 'minecraft:pickaxes', 'minecraft:axes',
              'minecraft:shovels', 'minecraft:hoes']
ARMOR_TAGS = ['minecraft:head_armor', 'minecraft:chest_armor',
              'minecraft:leg_armor', 'minecraft:foot_armor']
FOOD_TAGS  = ['minecraft:meat', 'minecraft:fishes', 'minecraft:cat_food']


def _load_stats(filename: str, columns: list[str]) -> dict[str, dict]:
    """Parse a pipe-separated stats file into {item_id: {col: value}}."""
    result: dict[str, dict] = {}
    for line in load_pipe_file(LISTS, filename):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 1 + len(columns):
            continue
        item_id = parts[0]
        result[item_id] = {
            col: (float(parts[i + 1]) if parts[i + 1] not in ('null', 'None', '') else None)
            for i, col in enumerate(columns)
        }
    return result


def _load_item_extra() -> dict[str, dict]:
    """Parse item_extra.txt into {item_id: {key: value}} from /dump_item_extra."""
    result: dict[str, dict] = {}
    def _i(v): return int(v)   if v not in ('null', 'None', '') else None
    def _f(v): return float(v) if v not in ('null', 'None', '') else None
    def _s(v): return v        if v not in ('null', 'None', '') else None
    for line in load_pipe_file(LISTS, 'item_extra.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 5:
            continue
        result[parts[0]] = {
            'maxStackSize':     _i(parts[1]),
            'enchantability':   _i(parts[2]),
            'miningEfficiency': _f(parts[3]),
            'miningTier':       _s(parts[4]) if parts[4] not in ('null', 'None', '') else None,
            # rarity (parts[5]) not used by subtypes
        }
    return result


def _items_from_tags(tags: list[str], tag_map: dict, mc_items: set) -> list[tuple[str, str]]:
    resolved: set[str] = set()
    for tag in tags:
        resolved |= resolve_tag(tag, tag_map)
    return sorted(
        (item_id.split(':', 1)[0], item_id.split(':', 1)[1])
        for item_id in resolved
        if item_id.startswith('minecraft:') and item_id in mc_items
    )


def _stat(stats: dict, key: str):
    return stats.get(key) if stats else None


def _load_data() -> dict:
    mc_items    = {i for i in load_registry(LISTS, 'items.txt') if i.startswith('minecraft:')}
    tag_map     = load_tag_map(LISTS, 'item_tags.txt')
    tool_stats  = _load_stats('tool_stats.txt',  ['attackDamage', 'attackSpeed', 'durability'])
    armor_stats = _load_stats('armor_stats.txt', ['armorPoints', 'armorToughness', 'knockbackResistance', 'durability'])
    food_stats  = _load_stats('food_stats.txt',  ['hungerRestoration', 'saturation'])
    item_extra  = _load_item_extra()
    print(f'tool_stats  entries : {len(tool_stats)}')
    print(f'armor_stats entries : {len(armor_stats)}')
    print(f'food_stats  entries : {len(food_stats)}')
    print(f'item_extra  entries : {len(item_extra)}')
    return {
        'mc_items':    mc_items,
        'tag_map':     tag_map,
        'tool_stats':  tool_stats,
        'armor_stats': armor_stats,
        'food_stats':  food_stats,
        'item_extra':  item_extra,
    }


def _classify_items(data: dict) -> tuple[list, list, list]:
    mc_items    = data['mc_items']
    tag_map     = data['tag_map']
    food_stats  = data['food_stats']

    tool_pairs  = _items_from_tags(TOOL_TAGS,  tag_map, mc_items)
    armor_pairs = _items_from_tags(ARMOR_TAGS, tag_map, mc_items)
    food_pairs  = (
        sorted(
            (item_id.split(':', 1)[0], item_id.split(':', 1)[1])
            for item_id in food_stats
            if item_id.startswith('minecraft:') and item_id in mc_items
        )
        if food_stats else _items_from_tags(FOOD_TAGS, tag_map, mc_items)
    )

    print(f'Tool  rows : {len(tool_pairs)}')
    print(f'Armor rows : {len(armor_pairs)}')
    print(f'Food  rows : {len(food_pairs)}')
    return tool_pairs, armor_pairs, food_pairs


def _build_tool_rows(tool_pairs: list, tool_stats: dict, item_extra: dict) -> list[tuple]:
    return [
        (ns, name,
         _stat(item_extra.get(f'minecraft:{name}'),  'miningEfficiency'),
         _stat(item_extra.get(f'minecraft:{name}'),  'miningTier'),
         _stat(tool_stats.get(f'minecraft:{name}'),  'attackDamage'),
         _stat(tool_stats.get(f'minecraft:{name}'),  'attackSpeed'),
         _stat(tool_stats.get(f'minecraft:{name}'),  'durability'),
         _stat(item_extra.get(f'minecraft:{name}'),  'enchantability'))
        for ns, name in tool_pairs
    ]


def _build_armor_rows(armor_pairs: list, armor_stats: dict, item_extra: dict) -> list[tuple]:
    return [
        (ns, name,
         _stat(armor_stats.get(f'minecraft:{name}'), 'armorPoints'),
         _stat(armor_stats.get(f'minecraft:{name}'), 'armorToughness'),
         _stat(armor_stats.get(f'minecraft:{name}'), 'durability'),
         _stat(item_extra.get(f'minecraft:{name}'),  'enchantability'),
         _stat(armor_stats.get(f'minecraft:{name}'), 'knockbackResistance'))
        for ns, name in armor_pairs
    ]


def _build_food_rows(food_pairs: list, food_stats: dict) -> list[tuple]:
    return [
        (ns, name,
         _stat(food_stats.get(f'minecraft:{name}'), 'hungerRestoration'),
         _stat(food_stats.get(f'minecraft:{name}'), 'saturation'))
        for ns, name in food_pairs
    ]


def main() -> None:
    data = _load_data()
    tool_pairs, armor_pairs, food_pairs = _classify_items(data)

    write_sql(OUTPUT, '15 - Minecraft item subtypes (Tool, Armor, Food)', [
        ('Tool — classified via item tags; attack stats from /dump_tool_stats, tier stats from /dump_item_extra',
         insert_rows(
             'Tool',
             ['item_namespace', 'item_name',
              'miningEfficiency', 'miningTier',
              'attackDamage', 'attackSpeed',
              'durability', 'enchantability'],
             _build_tool_rows(tool_pairs, data['tool_stats'], data['item_extra']),
         )),

        ('Armor — classified via item tags; stats from /dump_armor_stats, enchantability from /dump_item_extra',
         insert_rows(
             'Armor',
             ['item_namespace', 'item_name',
              'armorPoints', 'armorToughness',
              'durability', 'enchantability', 'knockbackResistance'],
             _build_armor_rows(armor_pairs, data['armor_stats'], data['item_extra']),
         )),

        ('Food — from /dump_food_stats when available, else item tags',
         insert_rows(
             'Food',
             ['item_namespace', 'item_name',
              'hungerRestoration', 'saturation'],
             _build_food_rows(food_pairs, data['food_stats']),
         )),
    ])


main()
