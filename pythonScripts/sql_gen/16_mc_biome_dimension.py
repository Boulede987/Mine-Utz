"""
sql_gen/16_mc_biome_dimension.py
=================================
Generates SQL inserts for BiomeDimension — vanilla + modded.

Source : lists/dimension_biomes.txt  (produced by extract_dimension_biomes.py)
         lists/biomes.txt            (FK validation)
         lists/dimensions.txt        (FK validation)

Output: BDD/Scripts/inserts/16_mc_biome_dimension.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/16_mc_biome_dimension.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths     import LISTS, ROOT
from lib.loaders   import load_registry, load_pipe_file

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '16_mc_biome_dimension.sql'

# ── Load valid sets for FK checks ─────────────────────────────────────────────

all_biomes     = load_registry(LISTS, 'biomes.txt')
all_dimensions = load_registry(LISTS, 'dimensions.txt')

# ── Parse dimension_biomes.txt ────────────────────────────────────────────────

rows: list[tuple] = []
seen: set[tuple]  = set()
skipped           = 0

for line in load_pipe_file(LISTS, 'dimension_biomes.txt'):
    parts = [p.strip() for p in line.split('|')]
    if len(parts) < 2:
        continue
    dim_id, biome_id = parts[0], parts[1]

    if not dim_id.startswith('minecraft:') or not biome_id.startswith('minecraft:'):
        skipped += 1
        continue
    if dim_id not in all_dimensions or biome_id not in all_biomes:
        skipped += 1
        continue

    dim_ns,   dim_name   = dim_id.split(':', 1)
    biome_ns, biome_name = biome_id.split(':', 1)

    pk = (biome_ns, biome_name, dim_ns, dim_name)
    if pk not in seen:
        seen.add(pk)
        rows.append(pk)

print(f'BiomeDimension rows : {len(rows)}')
print(f'Skipped (FK miss)   : {skipped}')

# ── Write ─────────────────────────────────────────────────────────────────────

write_sql(OUTPUT, '16 - Biome-dimension mapping', [
    ('BiomeDimension — sourced from biome tags (vanilla dims) + dimension JSON (mod dims)',
     insert_rows(
         'BiomeDimension',
         ['biome_namespace', 'biome_name', 'dimension_namespace', 'dimension_name'],
         rows,
     )),
])
