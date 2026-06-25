"""
sql_gen/02_mc_biomes.py
========================
Generates SQL inserts for the Biome table — vanilla Minecraft 1.21.1 only.

Sources : lists/biomes.txt      — namespace:name, one per line
          lists/biome_stats.txt — temperature, mainColor (fog hex) from /dump_biome_stats
Filter  : minecraft: namespace only

biome_stats.txt is optional; if absent, mainColor and temperature are NULL.

Output: BDD/Scripts/inserts/02_mc_biomes.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/02_mc_biomes.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_registry, load_pipe_file

OUTPUT = SQL_INSERTS / '02_mc_biomes.sql'


def _load_biome_stats() -> dict[str, tuple]:
    """Parse biome_stats.txt into {biome_id: (temperature, mainColor)}."""
    result: dict[str, tuple] = {}
    for line in load_pipe_file(LISTS, 'biome_stats.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 3:
            continue
        biome_id = parts[0]
        temp  = float(parts[1]) if parts[1] not in ('null', 'None', '') else None
        color = parts[2]        if parts[2] not in ('null', 'None', '') else None
        result[biome_id] = (temp, color)
    return result


def _load_vanilla_biome_rows(biome_stats: dict) -> list[tuple]:
    all_biomes = load_registry(LISTS, 'biomes.txt')
    mc_biomes  = sorted(b for b in all_biomes if b.startswith('minecraft:'))
    print(f'Found {len(mc_biomes)} vanilla biomes.  ({len(biome_stats)} with stats)')
    return [
        ('minecraft', name.split(':', 1)[1],
         biome_stats.get(name, (None, None))[1],   # mainColor (fog hex)
         biome_stats.get(name, (None, None))[0])   # temperature
        for name in mc_biomes
    ]


def main() -> None:
    biome_stats = _load_biome_stats()
    rows = _load_vanilla_biome_rows(biome_stats)
    write_sql(OUTPUT, '02 - Minecraft biomes', [
        ('Biome — vanilla 1.21.1  (mainColor=fog hex, temperature from /dump_biome_stats if available)',
         insert_rows('Biome', ['namespace', 'name', 'mainColor', 'temperature'], rows)),
    ])


main()
