"""
sql_gen/11_mc_dimensions.py
============================
Generates SQL inserts for the Dimension table — vanilla Minecraft 1.21.1.

Source : lists/dimensions.txt  (namespace:name, one per line)
Filter : minecraft: namespace only

Output: BDD/Scripts/inserts/11_mc_dimensions.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/11_mc_dimensions.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_registry

OUTPUT = SQL_INSERTS / '11_mc_dimensions.sql'


def _load_vanilla_dimension_rows() -> list[tuple]:
    all_dimensions = load_registry(LISTS, 'dimensions.txt')
    mc_dimensions  = sorted(d for d in all_dimensions if d.startswith('minecraft:'))
    print(f'Found {len(mc_dimensions)} vanilla dimensions.')
    return [('minecraft', d.split(':', 1)[1]) for d in mc_dimensions]


def main() -> None:
    rows = _load_vanilla_dimension_rows()
    write_sql(OUTPUT, '11 - Minecraft dimensions', [
        ('Dimension — vanilla 1.21.1',
         insert_rows('Dimension', ['namespace', 'name'], rows)),
    ])


main()
