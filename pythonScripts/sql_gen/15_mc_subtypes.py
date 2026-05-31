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

Stats files are optional: if absent, stat columns are NULL.
Run /dump_tool_stats, /dump_armor_stats, /dump_food_stats in-game then
extract_from_log.py to produce them.

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

# ── Load registry & tags ──────────────────────────────────────────────────────

mc_items = {i for i in load_registry(LISTS, 'items.txt') if i.startswith('minecraft:')}
tag_map  = load_tag_map(LISTS, 'item_tags.txt')

# ── Load stats (optional) ─────────────────────────────────────────────────────

def load_stats(filename: str, columns: list[str]) -> dict[str, dict]:
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

tool_stats  = load_stats('tool_stats.txt',  ['attackDamage', 'attackSpeed', 'durability'])
armor_stats = load_stats('armor_stats.txt', ['armorPoints', 'armorToughness', 'knockbackResistance', 'durability'])
food_stats  = load_stats('food_stats.txt',  ['hungerRestoration', 'saturation'])

print(f'tool_stats  entries : {len(tool_stats)}')
print(f'armor_stats entries : {len(armor_stats)}')
print(f'food_stats  entries : {len(food_stats)}')

# ── Classify via tags ─────────────────────────────────────────────────────────

def items_from_tags(tags: list[str]) -> list[tuple[str, str]]:
    resolved: set[str] = set()
    for tag in tags:
        resolved |= resolve_tag(tag, tag_map)
    return sorted(
        (id_.split(':', 1)[0], id_.split(':', 1)[1])
        for id_ in resolved
        if id_.startswith('minecraft:') and id_ in mc_items
    )

# Tag names as of 1.21.1 (vanilla renamed armor tags; food split into meat/fish/etc.)
TOOL_TAGS  = ['minecraft:swords', 'minecraft:pickaxes', 'minecraft:axes',
              'minecraft:shovels', 'minecraft:hoes']
ARMOR_TAGS = ['minecraft:head_armor', 'minecraft:chest_armor',
              'minecraft:leg_armor', 'minecraft:foot_armor']
FOOD_TAGS  = ['minecraft:meat', 'minecraft:fishes', 'minecraft:cat_food']

# Food classification uses stats dump when available (catches items not in tags)
if food_stats:
    food_pairs = sorted(
        (id_.split(':', 1)[0], id_.split(':', 1)[1])
        for id_ in food_stats
        if id_.startswith('minecraft:') and id_ in mc_items
    )
else:
    food_pairs = items_from_tags(FOOD_TAGS)

tool_pairs  = items_from_tags(TOOL_TAGS)
armor_pairs = items_from_tags(ARMOR_TAGS)

print(f'Tool  rows : {len(tool_pairs)}')
print(f'Armor rows : {len(armor_pairs)}')
print(f'Food  rows : {len(food_pairs)}')

# ── Build rows ────────────────────────────────────────────────────────────────

def s(stats: dict, key: str):
    return stats.get(key) if stats else None

tool_rows = [
    (ns, name,
     None, None,  # miningEfficiency, miningLevel — need separate dump
     s(tool_stats.get(f'minecraft:{name}'), 'attackDamage'),
     s(tool_stats.get(f'minecraft:{name}'), 'attackSpeed'),
     None,  # attackRange — not in attribute system
     s(tool_stats.get(f'minecraft:{name}'), 'durability'),
     None)  # enchantability — not in attribute system
    for ns, name in tool_pairs
]

armor_rows = [
    (ns, name,
     s(armor_stats.get(f'minecraft:{name}'), 'armorPoints'),
     s(armor_stats.get(f'minecraft:{name}'), 'armorToughness'),
     s(armor_stats.get(f'minecraft:{name}'), 'durability'),
     None,  # enchantability
     s(armor_stats.get(f'minecraft:{name}'), 'knockbackResistance'))
    for ns, name in armor_pairs
]

food_rows = [
    (ns, name,
     s(food_stats.get(f'minecraft:{name}'), 'hungerRestoration'),
     s(food_stats.get(f'minecraft:{name}'), 'saturation'))
    for ns, name in food_pairs
]

# ── Write ─────────────────────────────────────────────────────────────────────

write_sql(OUTPUT, '15 - Minecraft item subtypes (Tool, Armor, Food)', [
    ('Tool — classified via item tags; attack stats from /dump_tool_stats when available',
     insert_rows(
         'Tool',
         ['item_namespace', 'item_name',
          'miningEfficiency', 'miningLevel',
          'attackDamage', 'attackSpeed', 'attackRange',
          'durability', 'enchantability'],
         tool_rows,
     )),

    ('Armor — classified via item tags; stats from /dump_armor_stats when available',
     insert_rows(
         'Armor',
         ['item_namespace', 'item_name',
          'armorPoints', 'armorToughness',
          'durability', 'enchantability', 'knockbackResistance'],
         armor_rows,
     )),

    ('Food — from /dump_food_stats when available, else item tags',
     insert_rows(
         'Food',
         ['item_namespace', 'item_name',
          'hungerRestoration', 'saturation'],
         food_rows,
     )),
])
