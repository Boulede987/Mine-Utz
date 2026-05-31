"""
sql_gen/12_mc_structure_spawns.py
==================================
Generates SQL inserts for StructureSpawnTable — vanilla Minecraft 1.21.1.

Source : lists/structure_spawns.txt
         (produced by extract_from_jars.py via the extended structure collector)
Filter : structure and entity must both be minecraft: namespace

Output: BDD/Scripts/inserts/12_mc_structure_spawns.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/12_mc_structure_spawns.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths   import LISTS, SQL_INSERTS
from lib.loaders import load_pipe_file, load_registry

OUTPUT = SQL_INSERTS / '12_mc_structure_spawns.sql'


def _parse_int(field: str) -> int:
    return int(field.split(':', 1)[1]) if ':' in field else 1


def _load_valid_sets() -> tuple[set[str], set[str]]:
    mc_structures = {s for s in load_registry(LISTS, 'structures.txt') if s.startswith('minecraft:')}
    mc_entities   = {e for e in load_registry(LISTS, 'mobs.txt')       if e.startswith('minecraft:')}
    return mc_structures, mc_entities


def _parse_structure_spawn_rows(mc_structures: set, mc_entities: set) -> list[tuple]:
    rows: list[tuple] = []
    seen: set[tuple]  = set()
    for line in load_pipe_file(LISTS, 'structure_spawns.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 5:
            continue
        structure, entity = parts[0], parts[1]
        if not structure.startswith('minecraft:') or not entity.startswith('minecraft:'):
            continue
        if structure not in mc_structures or entity not in mc_entities:
            continue
        str_ns, str_name = structure.split(':', 1)
        ent_ns, ent_name = entity.split(':', 1)
        pk = (str_ns, str_name, ent_ns, ent_name)
        if pk not in seen:
            seen.add(pk)
            rows.append(pk)
    print(f'StructureSpawnTable rows: {len(rows)}')
    return rows


def main() -> None:
    mc_structures, mc_entities = _load_valid_sets()
    rows = _parse_structure_spawn_rows(mc_structures, mc_entities)
    write_sql(OUTPUT, '12 - Minecraft structure spawn overrides', [
        ('StructureSpawnTable — entities that spawn inside structures',
         insert_rows(
             'StructureSpawnTable',
             ['structure_namespace', 'structure_name', 'entity_namespace', 'entity_name'],
             rows,
         )),
    ])


main()
