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
    '15_mc_subtypes.py',
    '08_mc_recipes.py',
    '09_mc_loot_structure.py',
    '18_mc_loot_block.py',
    '10_mc_entity_textures.py',
    '11_mc_dimensions.py',
    '12_mc_structure_spawns.py',
    '13_mc_structure_blocks.py',
    '14_mc_biome_blocks.py',
    '16_mc_biome_dimension.py',
    '17_mc_entity_dimension.py',
]


def _run_script(script_name: str) -> bool:
    print(f'\n{"="*55}')
    print(f'  {script_name}')
    print(f'{"="*55}')
    sys.stdout.flush()
    result = subprocess.run([sys.executable, str(SQL_GEN / script_name)])
    if result.returncode != 0:
        print(f'  !! FAILED (exit {result.returncode})')
        return False
    return True


def main() -> None:
    failed_scripts = [s for s in SCRIPTS if not _run_script(s)]

    print(f'\n{"="*55}')
    if failed_scripts:
        print(f'DONE — {len(failed_scripts)} script(s) failed:')
        for failed_script in failed_scripts:
            print(f'  - {failed_script}')
        sys.exit(1)
    else:
        print(f'DONE — all {len(SCRIPTS)} scripts completed successfully.')


main()
