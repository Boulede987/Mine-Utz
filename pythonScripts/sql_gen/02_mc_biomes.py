"""
sql_gen/02_mc_biomes.py
========================
Generates SQL inserts for the Biome table — vanilla Minecraft 1.21.1 only.

Source : lists/biomes.txt  (namespace:name, one per line)
Filter : minecraft: namespace only
Fields : namespace, name  (mainColor and temperature left NULL — not in registry dump)

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
from lib.loaders import load_registry

OUTPUT = SQL_INSERTS / '02_mc_biomes.sql'


def _load_vanilla_biome_rows() -> list[tuple]:
    all_biomes = load_registry(LISTS, 'biomes.txt')
    mc_biomes  = sorted(b for b in all_biomes if b.startswith('minecraft:'))
    print(f'Found {len(mc_biomes)} vanilla biomes.')
    return [
        ('minecraft', name.split(':', 1)[1], None, None)
        for name in mc_biomes
    ]


def main() -> None:
    rows = _load_vanilla_biome_rows()
    write_sql(OUTPUT, '02 - Minecraft biomes', [
        ('Biome — vanilla 1.21.1  (mainColor and temperature are NULL)',
         insert_rows('Biome', ['namespace', 'name', 'mainColor', 'temperature'], rows)),
    ])


main()
