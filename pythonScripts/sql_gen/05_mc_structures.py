"""
sql_gen/05_mc_structures.py
============================
Generates SQL inserts for the Structure table — vanilla Minecraft 1.21.1.

Source : lists/structures.txt  (namespace:name, one per line)
Filter : minecraft: namespace only

loottable_id is left NULL here; it will be linkable once loot tables are
inserted in a later step.

Output: BDD/Scripts/inserts/05_mc_structures.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/05_mc_structures.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_registry

OUTPUT = SQL_INSERTS / '05_mc_structures.sql'


def _load_vanilla_structure_rows() -> list[tuple]:
    all_structures = load_registry(LISTS, 'structures.txt')
    mc_structures  = sorted(s for s in all_structures if s.startswith('minecraft:'))
    print(f'Found {len(mc_structures)} vanilla structures.')
    return [
        ('minecraft', name.split(':', 1)[1], None)
        for name in mc_structures
    ]


def main() -> None:
    rows = _load_vanilla_structure_rows()
    write_sql(OUTPUT, '05 - Minecraft structures', [
        ('Structure — vanilla 1.21.1  (loottable_id is NULL — populated in loot step)',
         insert_rows('Structure', ['namespace', 'name', 'loottable_id'], rows)),
    ])


main()
