"""
sql_gen/14_mc_biome_blocks.py
==============================
Generates SQL inserts for BlockBiomeGenerationTable — vanilla Minecraft 1.21.1.

Source : lists/biome_blocks.txt
         (produced by extract_biome_blocks.py)
         Format: biome_id | block_id

Filter : both biome and block must be minecraft: namespace and must exist
         in the respective registry lists (FK validation).

Output: BDD/Scripts/inserts/14_mc_biome_blocks.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/14_mc_biome_blocks.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths   import LISTS, ROOT
from lib.loaders import load_pipe_file, load_registry

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '14_mc_biome_blocks.sql'

# ── Load valid sets for FK checks ─────────────────────────────────────────────

mc_biomes = {b for b in load_registry(LISTS, 'biomes.txt') if b.startswith('minecraft:')}
mc_blocks = {b for b in load_registry(LISTS, 'blocks.txt') if b.startswith('minecraft:')}

# ── Parse biome_blocks.txt ────────────────────────────────────────────────────

rows: list[tuple] = []
seen: set[tuple]  = set()

for line in load_pipe_file(LISTS, 'biome_blocks.txt'):
    parts = [p.strip() for p in line.split('|')]
    if len(parts) < 2:
        continue

    biome_id, block_id = parts[0], parts[1]
    if not biome_id.startswith('minecraft:') or not block_id.startswith('minecraft:'):
        continue
    if biome_id not in mc_biomes or block_id not in mc_blocks:
        continue

    bio_ns,  bio_name  = biome_id.split(':', 1)
    blk_ns,  blk_name  = block_id.split(':', 1)

    pk = (blk_ns, blk_name, bio_ns, bio_name)
    if pk not in seen:
        seen.add(pk)
        rows.append(pk)

print(f'BlockBiomeGenerationTable rows: {len(rows)}')

# ── Write ─────────────────────────────────────────────────────────────────────

write_sql(OUTPUT, '14 - Minecraft block-biome generation', [
    ('BlockBiomeGenerationTable — ore/disk blocks that generate in each biome',
     insert_rows(
         'BlockBiomeGenerationTable',
         ['block_namespace', 'block_name', 'biome_namespace', 'biome_name'],
         rows,
     )),
])
