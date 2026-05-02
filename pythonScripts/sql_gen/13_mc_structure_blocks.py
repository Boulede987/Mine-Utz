"""
sql_gen/13_mc_structure_blocks.py
===================================
Generates SQL inserts for BlockStructureGenerationTable — vanilla 1.21.1.

Source : lists/structure_blocks.txt
         (produced by extract_structure_loottables.py)
         Format: structure_id | block_name | count

structure_id is already a registered structure ID (resolved by the extractor).
Block counts are summed across all templates that share the same structure_id.

Filter : both structure and block must be minecraft: namespace and must exist
         in the respective registry lists (FK validation).

Output: BDD/Scripts/inserts/13_mc_structure_blocks.sql
"""

import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths   import LISTS, ROOT
from lib.loaders import load_pipe_file, load_registry

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '13_mc_structure_blocks.sql'

# ── Load valid sets for FK checks ─────────────────────────────────────────────

mc_structures = {s for s in load_registry(LISTS, 'structures.txt') if s.startswith('minecraft:')}
mc_blocks     = {b for b in load_registry(LISTS, 'blocks.txt')     if b.startswith('minecraft:')}

# ── Parse structure_blocks.txt and aggregate counts ───────────────────────────

# (str_ns, str_name, blk_ns, blk_name) → total count across all templates
aggregated: dict[tuple, int] = defaultdict(int)

for line in load_pipe_file(LISTS, 'structure_blocks.txt'):
    parts = [p.strip() for p in line.split('|')]
    if len(parts) < 3:
        continue
    structure_id, block_name = parts[0], parts[1]
    try:
        count = int(parts[2])
    except ValueError:
        count = 1

    if not structure_id.startswith('minecraft:') or not block_name.startswith('minecraft:'):
        continue
    if structure_id not in mc_structures or block_name not in mc_blocks:
        continue

    str_ns, str_name = structure_id.split(':', 1)
    blk_ns, blk_name = block_name.split(':', 1)
    aggregated[(str_ns, str_name, blk_ns, blk_name)] += count

rows = [
    (blk_ns, blk_name, str_ns, str_name, count)
    for (str_ns, str_name, blk_ns, blk_name), count in sorted(aggregated.items())
]

print(f'BlockStructureGenerationTable rows: {len(rows)}')

# ── Write ─────────────────────────────────────────────────────────────────────

write_sql(OUTPUT, '13 - Minecraft block-structure generation', [
    ('BlockStructureGenerationTable — blocks found in structure templates (aggregated count)',
     insert_rows(
         'BlockStructureGenerationTable',
         ['block_namespace', 'block_name', 'structure_namespace', 'structure_name', 'number'],
         rows,
     )),
])
