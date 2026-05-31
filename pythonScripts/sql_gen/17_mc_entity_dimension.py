"""
sql_gen/17_mc_entity_dimension.py
===================================
Generates SQL inserts for EntityDimension — derived, not extracted.

Two derivation paths:

  1. Biome spawns:   entity spawns in biome B  +  B is in dimension D
                     (biome_spawns.txt + dimension_biomes.txt)

  2. Structure spawns: entity spawns in structure S  +  S generates in biome B
                       +  B is in dimension D
                       (structure_spawns.txt + structure_biomes.txt + dimension_biomes.txt)
                       Catches end-only entities like shulkers (End City).

Sources : lists/biome_spawns.txt      — biome | entity | weight | min | max
          lists/structure_spawns.txt  — structure | entity | weight | min | max
          lists/structure_biomes.txt  — structure | biome_or_tag
          lists/dimension_biomes.txt  — dimension_id | biome_id
          lists/mobs.txt              — FK validation
          lists/dimensions.txt        — FK validation

Output: BDD/Scripts/inserts/17_mc_entity_dimension.sql
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils    import insert_rows, write_sql
from lib.paths        import LISTS, SQL_INSERTS
from lib.loaders      import load_registry, load_pipe_file
from lib.tag_resolver import load_tag_map, resolve_tag

OUTPUT = SQL_INSERTS / '17_mc_entity_dimension.sql'


def _build_biome_to_dims(all_dimensions: set) -> dict[str, set[str]]:
    biome_to_dims: dict[str, set[str]] = {}
    for line in load_pipe_file(LISTS, 'dimension_biomes.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 2:
            continue
        dim_id, biome_id = parts[0], parts[1]
        if dim_id in all_dimensions:
            biome_to_dims.setdefault(biome_id, set()).add(dim_id)
    return biome_to_dims


def _build_structure_to_dims(biome_to_dims: dict) -> dict[str, set[str]]:
    tag_map = load_tag_map(LISTS)
    structure_to_dims: dict[str, set[str]] = {}
    for line in load_pipe_file(LISTS, 'structure_biomes.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 2:
            continue
        structure_id, biome_or_tag = parts[0], parts[1]
        biomes = (
            resolve_tag(biome_or_tag[1:], tag_map) if biome_or_tag.startswith('#')
            else {biome_or_tag}
        )
        for biome_id in biomes:
            for dim_id in biome_to_dims.get(biome_id, set()):
                structure_to_dims.setdefault(structure_id, set()).add(dim_id)
    return structure_to_dims


def _add_row(
    entity_id: str,
    dim_id: str,
    all_entities: set,
    all_dimensions: set,
    seen: set,
    rows: list,
) -> None:
    if not entity_id.startswith('minecraft:') or not dim_id.startswith('minecraft:'):
        return
    if entity_id not in all_entities or dim_id not in all_dimensions:
        return
    ent_ns, ent_name = entity_id.split(':', 1)
    dim_ns, dim_name = dim_id.split(':', 1)
    pk = (ent_ns, ent_name, dim_ns, dim_name)
    if pk not in seen:
        seen.add(pk)
        rows.append(pk)


def _collect_from_biome_spawns(biome_to_dims: dict, all_entities: set, all_dimensions: set) -> tuple[list, set]:
    seen: set[tuple] = set()
    rows: list[tuple] = []
    for line in load_pipe_file(LISTS, 'biome_spawns.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 2:
            continue
        biome_id, entity_id = parts[0], parts[1]
        for dim_id in biome_to_dims.get(biome_id, set()):
            _add_row(entity_id, dim_id, all_entities, all_dimensions, seen, rows)
    return rows, seen


def _collect_from_structure_spawns(
    structure_to_dims: dict,
    all_entities: set,
    all_dimensions: set,
    seen: set,
    rows: list,
) -> None:
    for source_file in ('structure_spawns.txt', 'structure_nbt_entities.txt'):
        for line in load_pipe_file(LISTS, source_file):
            parts = [p.strip() for p in line.split('|')]
            if len(parts) < 2:
                continue
            structure_id, entity_id = parts[0], parts[1]
            for dim_id in structure_to_dims.get(structure_id, set()):
                _add_row(entity_id, dim_id, all_entities, all_dimensions, seen, rows)


def main() -> None:
    all_entities   = load_registry(LISTS, 'mobs.txt')
    all_dimensions = load_registry(LISTS, 'dimensions.txt')

    biome_to_dims     = _build_biome_to_dims(all_dimensions)
    structure_to_dims = _build_structure_to_dims(biome_to_dims)

    rows, seen = _collect_from_biome_spawns(biome_to_dims, all_entities, all_dimensions)
    _collect_from_structure_spawns(structure_to_dims, all_entities, all_dimensions, seen, rows)

    rows.sort()
    print(f'EntityDimension rows : {len(rows)}')

    write_sql(OUTPUT, '17 - Entity-dimension mapping (derived from biome + structure spawns)', [
        ('EntityDimension — derived via biome spawns and structure spawns',
         insert_rows(
             'EntityDimension',
             ['entity_namespace', 'entity_name', 'dimension_namespace', 'dimension_name'],
             rows,
         )),
    ])


main()
