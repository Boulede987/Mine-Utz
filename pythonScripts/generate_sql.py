"""
generate_sql.py
===============
Top-level runner: executes all sql_gen scripts in order.

Each script generates one SQL INSERT file under BDD/Scripts/inserts/.
Scripts must be run in order because later ones depend on rows
inserted by earlier ones (FK constraints).

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/generate_sql.py
"""

import subprocess
import sys
from pathlib import Path

SQL_GEN = Path(__file__).parent / 'sql_gen'

SCRIPTS = [
    '01_mc_bootstrap.py',
    '02_mc_biomes.py',
    '03_mc_items.py',
    '04_mc_entities.py',
    '05_mc_structures.py',
    '06_mc_loot.py',
    '07_mc_relations.py',
    '08_mc_recipes.py',
    '09_mc_loot_structure.py',
]

failures = []

for script_name in SCRIPTS:
    script_path = SQL_GEN / script_name
    print(f'\n{"="*55}')
    print(f'  {script_name}')
    print(f'{"="*55}')
    sys.stdout.flush()

    result = subprocess.run([sys.executable, str(script_path)])

    if result.returncode != 0:
        print(f'  !! FAILED (exit {result.returncode})')
        failures.append(script_name)

print(f'\n{"="*55}')
if failures:
    print(f'DONE — {len(failures)} script(s) failed:')
    for f in failures:
        print(f'  - {f}')
    sys.exit(1)
else:
    print(f'DONE — all {len(SCRIPTS)} scripts completed successfully.')
