"""
sql_gen/03_mc_items.py
=======================
Generates SQL inserts for the Item and Block tables — vanilla Minecraft 1.21.1.

Sources : lists/items.txt, lists/blocks.txt
Filter  : minecraft: namespace only

Logic:
  - Every minecraft: entry from items.txt  -> Item row
  - Every minecraft: entry that also appears in blocks.txt -> Block row
    (Block is a subtype of Item; the Item row must exist first)

Fields left NULL (not available from registry dumps):
  Item  : rarity_id, renewable, stackable
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
from lib.paths import LISTS, ROOT
from lib.loaders import load_registry

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '03_mc_items.sql'

# ── Load and filter ───────────────────────────────────────────────────────────

all_items  = load_registry(LISTS, 'items.txt')
all_blocks = load_registry(LISTS, 'blocks.txt')

mc_items  = sorted(e for e in all_items  if e.startswith('minecraft:'))
mc_blocks = sorted(e for e in all_blocks if e.startswith('minecraft:'))

# Items that are also blocks (intersection of both sets, filtered to minecraft:)
mc_block_items = set(mc_blocks) & set(mc_items)

print(f'Vanilla items : {len(mc_items)}')
print(f'Vanilla blocks: {len(mc_blocks)}')
print(f'  of which also in items.txt: {len(mc_block_items)}')
print(f'  blocks-only (no item form): {len(set(mc_blocks) - set(mc_items))}')

# ── Build rows ────────────────────────────────────────────────────────────────

item_rows = [
    ('minecraft', name.split(':', 1)[1], None, None, None)   # rarity_id, renewable, stackable
    for name in mc_items
]

block_rows = [
    ('minecraft', name.split(':', 1)[1], None, None, None, None, None)  # blastResistance, hardness, luminous, transparent, flammable
    for name in sorted(mc_block_items)
]

# ── Write ─────────────────────────────────────────────────────────────────────

write_sql(OUTPUT, '03 - Minecraft items and blocks', [
    ('Item — all vanilla items  (rarity, renewable, stackable are NULL)',
     insert_rows(
         'Item',
         ['namespace', 'name', 'rarity_id', 'renewable', 'stackable'],
         item_rows,
     )),

    ('Block — vanilla items that are also blocks  (physical properties are NULL)',
     insert_rows(
         'Block',
         ['item_namespace', 'item_name', 'blastResistance', 'hardness', 'luminous', 'transparent', 'flammable'],
         block_rows,
     )),
])
