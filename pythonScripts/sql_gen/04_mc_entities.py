"""
sql_gen/04_mc_entities.py
==========================
Generates SQL inserts for the Entity table — vanilla Minecraft 1.21.1.

Source : lists/mobs.txt  (namespace:name, one per line)
Filter : minecraft: namespace only

All combat/movement stats are left NULL — they are not available from the
registry dump. Only namespace and name are populated.

Output: BDD/Scripts/inserts/04_mc_entities.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/04_mc_entities.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_registry

OUTPUT = SQL_INSERTS / '04_mc_entities.sql'

# Columns: namespace, name, behavior, type, healthPoints, armorPoints,
#          attackDamage, attackSpeed, attackRange, speed,
#          knockbackResistance, weakness, hitboxSize, loottable_id


def _load_vanilla_entity_rows() -> list[tuple]:
    all_mobs = load_registry(LISTS, 'mobs.txt')
    mc_mobs  = sorted(e for e in all_mobs if e.startswith('minecraft:'))
    print(f'Found {len(mc_mobs)} vanilla entities.')
    return [
        ('minecraft', name.split(':', 1)[1],
         None, None, None, None, None, None, None, None, None, None, None, None)
        for name in mc_mobs
    ]


def main() -> None:
    rows = _load_vanilla_entity_rows()
    write_sql(OUTPUT, '04 - Minecraft entities', [
        ('Entity — vanilla 1.21.1  (all stats are NULL — not in registry dump)',
         insert_rows(
             'Entity',
             ['namespace', 'name', 'behavior', 'type',
              'healthPoints', 'armorPoints', 'attackDamage', 'attackSpeed',
              'attackRange', 'speed', 'knockbackResistance', 'weakness',
              'hitboxSize', 'loottable_id'],
             rows,
         )),
    ])


main()
