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
  UPDATE Entity      — sets Entity.loottable_id from entity_loottables.txt

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
from lib.paths import LISTS, ROOT
from lib.loaders import load_pipe_file

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '06_mc_loot.sql'

LOOT_FILES = ['loot_entities.txt', 'loot_chests.txt', 'loot_blocks.txt', 'loot_other.txt']


def parse_pipe_row(raw_line: str) -> list[str]:
    return [p.strip() for p in raw_line.split('|')]


def parse_int_field(field: str) -> int:
    """Parse fields like 'min:3', 'max:8', 'weight:4'."""
    return int(field.split(':', 1)[1]) if ':' in field else 1


# ── Collect all unique minecraft: loot table paths ────────────────────────────

loot_paths: set[str] = set()

for filename in LOOT_FILES:
    for line in load_pipe_file(LISTS, filename):
        source = parse_pipe_row(line)[0]
        if source.startswith('minecraft:'):
            loot_paths.add(source)

# Also collect referenced child tables
for line in load_pipe_file(LISTS, 'loot_references.txt'):
    parts = parse_pipe_row(line)
    for p in parts:
        if p.startswith('minecraft:'):
            loot_paths.add(p)

# Assign stable sequential IDs (sorted for reproducibility)
loot_id_map: dict[str, int] = {
    path: idx + 1
    for idx, path in enumerate(sorted(loot_paths))
}

print(f'Unique minecraft: loot tables : {len(loot_id_map)}')

# ── LootTable rows ────────────────────────────────────────────────────────────

loottable_rows = [(loot_id_map[path],) for path in sorted(loot_paths)]

# ── Comprises rows ────────────────────────────────────────────────────────────
# PK is (loottable_id, item_namespace, item_name) — deduplicate; last row wins.

comprises_map: dict[tuple, tuple] = {}   # (loot_id, ns, name) -> (min, max, weight)

for filename in LOOT_FILES:
    for line in load_pipe_file(LISTS, filename):
        parts = parse_pipe_row(line)
        if len(parts) < 5:
            continue
        source, item = parts[0], parts[1]
        if not source.startswith('minecraft:') or source not in loot_id_map:
            continue
        if not item.startswith('minecraft:'):
            continue   # skip mod-injected items — their Item rows don't exist yet

        loot_id  = loot_id_map[source]
        item_ns, item_name = item.split(':', 1)
        min_val  = parse_int_field(parts[2])
        max_val  = parse_int_field(parts[3])
        weight   = parse_int_field(parts[4])

        comprises_map[(loot_id, item_ns, item_name)] = (min_val, max_val, weight)

comprises_rows = [
    (loot_id, ns, name, mn, mx, w)
    for (loot_id, ns, name), (mn, mx, w) in sorted(comprises_map.items())
]

print(f'Comprises entries             : {len(comprises_rows)}')

# ── LootTableEntity rows ──────────────────────────────────────────────────────

lt_entity_rows: list[tuple] = []
entity_update_lines: list[str] = []

for line in load_pipe_file(LISTS, 'entity_loottables.txt'):
    parts = parse_pipe_row(line)
    if len(parts) < 2:
        continue
    entity, loot_path = parts[0], parts[1]
    if not entity.startswith('minecraft:') or not loot_path.startswith('minecraft:'):
        continue
    if loot_path not in loot_id_map:
        continue

    loot_id  = loot_id_map[loot_path]
    ent_ns, ent_name = entity.split(':', 1)

    lt_entity_rows.append((loot_id, ent_ns, ent_name))
    entity_update_lines.append(
        f"UPDATE Entity SET loottable_id = {loot_id}"
        f" WHERE namespace = 'minecraft' AND name = '{ent_name}';"
    )

print(f'LootTableEntity entries       : {len(lt_entity_rows)}')

# ── LootTableReference rows ───────────────────────────────────────────────────

lt_ref_rows: list[tuple] = []

for line in load_pipe_file(LISTS, 'loot_references.txt'):
    parts = parse_pipe_row(line)
    if len(parts) < 2:
        continue
    parent, child = parts[0], parts[1]
    if parent not in loot_id_map or child not in loot_id_map:
        continue
    lt_ref_rows.append((loot_id_map[parent], loot_id_map[child]))

print(f'LootTableReference entries    : {len(lt_ref_rows)}')

# ── Assemble SQL ──────────────────────────────────────────────────────────────

entity_updates_sql = '\n'.join(entity_update_lines) + '\n' if entity_update_lines else '-- (none)\n'

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
         lt_entity_rows,
     )),

    ('LootTableReference — nested loot table references',
     insert_rows(
         'LootTableReference',
         ['parent_id', 'child_id'],
         lt_ref_rows,
     )),

    ('UPDATE Entity.loottable_id from entity_loottables.txt',
     entity_updates_sql),
])
