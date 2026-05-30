from .db import run_batch
from .readers import read_ids, read_pipe, parse_kv, resolve_biome, loot_path_to_block_id, node_rows

_PRODUCES_QUERY = """
    UNWIND $rows AS r
    MATCH (rc:Recipe {id:r.recipe}), (i:Item {id:r.item})
    MERGE (rc)-[:PRODUCES {count:r.count}]->(i)
"""

_REQUIRES_QUERY = """
    UNWIND $rows AS r
    MATCH (rc:Recipe {id:r.recipe}), (i:Item {id:r.item})
    MERGE (rc)-[:REQUIRES]->(i)
"""

_USES_STATION_QUERY = """
    UNWIND $rows AS r
    MATCH (rc:Recipe {id:r.recipe}), (i:Item {id:r.station})
    MERGE (rc)-[:USES_STATION]->(i)
"""

_ENTITY_DROPS_QUERY = """
    UNWIND $rows AS r
    MATCH (e:Entity {id:r.entity}), (i:Item {id:r.item})
    MERGE (e)-[d:DROPS]->(i)
    SET d.min = r.min, d.max = r.max, d.weight = r.weight
"""

_CHEST_LOOT_QUERY = """
    UNWIND $rows AS r
    MATCH (st:Structure {id:r.structure}), (i:Item {id:r.item})
    MERGE (st)-[c:CONTAINS]->(i)
    SET c.min = r.min, c.max = r.max, c.weight = r.weight
"""

_BIOME_SPAWNS_QUERY = """
    UNWIND $rows AS r
    MATCH (e:Entity {id:r.entity}), (b:Biome {id:r.biome})
    MERGE (e)-[sp:SPAWNS_IN]->(b)
    SET sp.weight = r.weight, sp.min = r.min, sp.max = r.max
"""

_STRUCTURE_BIOMES_QUERY = """
    UNWIND $rows AS r
    MATCH (st:Structure {id:r.structure}), (b:Biome {id:r.biome})
    MERGE (st)-[:FOUND_IN]->(b)
"""

_DIMENSION_BIOMES_QUERY = """
    UNWIND $rows AS r
    MATCH (d:Dimension {id:r.dimension}), (b:Biome {id:r.biome})
    MERGE (b)-[:IN_DIMENSION]->(d)
"""

_STRUCTURE_SPAWNS_QUERY = """
    UNWIND $rows AS r
    MATCH (st:Structure {id:r.structure}), (e:Entity {id:r.entity})
    MERGE (e)-[:FOUND_IN_STRUCTURE]->(st)
"""

_MERGE_BLOCKS_QUERY = """
    UNWIND $rows AS r
    MERGE (b:Block {id: r.id})
    SET b.namespace = r.namespace, b.name = r.name
"""

_BLOCK_DROPS_QUERY = """
    UNWIND $rows AS r
    MATCH (b:Block {id:r.block}), (i:Item {id:r.item})
    MERGE (b)-[d:DROPS]->(i)
    SET d.min = r.min, d.max = r.max, d.weight = r.weight
"""


def export_recipes(session, item_ids: set[str]) -> None:
    recipe_nodes, produces_edges, requires_edges, station_edges = _parse_recipes(item_ids)
    run_batch(session, "UNWIND $rows AS r MERGE (rc:Recipe {id:r.id}) SET rc.type = r.type",
              recipe_nodes)
    run_batch(session, _PRODUCES_QUERY,    produces_edges)
    run_batch(session, _REQUIRES_QUERY,    requires_edges)
    run_batch(session, _USES_STATION_QUERY, station_edges)
    print(f"  {len(recipe_nodes)} recipes -> "
          f"{len(produces_edges)} outputs, {len(requires_edges)} ingredients, {len(station_edges)} stations")


def _parse_recipes(item_ids: set[str]) -> tuple[list, list, list, list]:
    recipe_nodes, produces_edges, requires_edges, station_edges = [], [], [], []
    for row in read_pipe('recipes.txt'):
        if len(row) < 5:
            continue
        recipe_id, recipe_type, station, result, count_raw = row[0], row[1], row[2], row[3], row[4]
        if result not in item_ids:
            continue
        try:
            count = int(count_raw.lstrip('x'))
        except ValueError:
            count = 1
        recipe_nodes.append({'id': recipe_id, 'type': recipe_type})
        produces_edges.append({'recipe': recipe_id, 'item': result, 'count': count})
        if station in item_ids:
            station_edges.append({'recipe': recipe_id, 'station': station})
        if len(row) > 5:
            for ingredient in row[5].split(','):
                ingredient = ingredient.strip()
                if ingredient and ingredient in item_ids:
                    requires_edges.append({'recipe': recipe_id, 'item': ingredient})
    return recipe_nodes, produces_edges, requires_edges, station_edges


def export_entity_loot(session, entity_ids: set[str], item_ids: set[str]) -> None:
    loot_to_entity = _load_entity_loot_table_mapping()
    drops = _build_entity_drop_edges(loot_to_entity, entity_ids, item_ids)
    run_batch(session, _ENTITY_DROPS_QUERY, drops)
    print(f"  {len(drops)} entity->item drop edges")


def _load_entity_loot_table_mapping() -> dict[str, str]:
    mapping = {}
    for row in read_pipe('entity_loottables.txt'):
        if len(row) == 2:
            mapping[row[1]] = row[0]
    return mapping


def _build_entity_drop_edges(loot_to_entity, entity_ids, item_ids) -> list[dict]:
    drops = []
    for row in read_pipe('loot_entities.txt'):
        if len(row) < 5:
            continue
        loot_path, item_id = row[0], row[1]
        entity_id = loot_to_entity.get(loot_path)
        if not entity_id or entity_id not in entity_ids or item_id not in item_ids:
            continue
        drops.append({'entity': entity_id, 'item': item_id,
                      'min': parse_kv(row[2]), 'max': parse_kv(row[3]), 'weight': parse_kv(row[4])})
    return drops


def export_chest_loot(session, structure_ids: set[str], item_ids: set[str]) -> None:
    loot_paths_by_structure = _load_structure_loot_paths()
    loot_by_path = _load_chest_loot_by_path(item_ids)
    contains = _build_contains_edges(structure_ids, loot_paths_by_structure, loot_by_path)
    run_batch(session, _CHEST_LOOT_QUERY, contains)
    print(f"  {len(contains)} structure->item chest edges")


def _load_structure_loot_paths() -> dict[str, list[str]]:
    paths_by_structure = {}
    for row in read_pipe('structure_loottables.txt'):
        if len(row) >= 4:
            paths_by_structure.setdefault(row[0], []).append(row[3])
    return paths_by_structure


def _load_chest_loot_by_path(item_ids: set[str]) -> dict[str, list[dict]]:
    loot_by_path = {}
    for row in read_pipe('loot_chests.txt'):
        if len(row) < 5:
            continue
        loot_path, item_id = row[0], row[1]
        if item_id not in item_ids:
            continue
        loot_by_path.setdefault(loot_path, []).append({
            'item':   item_id,
            'min':    parse_kv(row[2]),
            'max':    parse_kv(row[3]),
            'weight': parse_kv(row[4]),
        })
    return loot_by_path


def _build_contains_edges(structure_ids, loot_paths_by_structure, loot_by_path) -> list[dict]:
    contains = []
    for structure_id, loot_paths in loot_paths_by_structure.items():
        if structure_id not in structure_ids:
            continue
        seen = set()
        for loot_path in loot_paths:
            for entry in loot_by_path.get(loot_path, []):
                key = (structure_id, entry['item'])
                if key not in seen:
                    seen.add(key)
                    contains.append({'structure': structure_id, **entry})
    return contains


def export_biome_spawns(session, biome_ids: set[str], entity_ids: set[str]) -> None:
    spawns = []
    for row in read_pipe('biome_spawns.txt'):
        if len(row) < 5:
            continue
        biome_id, entity_id = row[0], row[1]
        if biome_id not in biome_ids or entity_id not in entity_ids:
            continue
        spawns.append({'biome': biome_id, 'entity': entity_id,
                       'weight': parse_kv(row[2]), 'min': parse_kv(row[3]), 'max': parse_kv(row[4])})
    run_batch(session, _BIOME_SPAWNS_QUERY, spawns)
    print(f"  {len(spawns)} entity->biome spawn edges")


def export_structure_biomes(session, structure_ids: set[str], biome_ids: set[str]) -> None:
    edges = []
    for row in read_pipe('structure_biomes.txt'):
        if len(row) < 2:
            continue
        structure_id, biome_raw = row[0], row[1]
        if biome_raw.startswith('#'):
            continue
        if structure_id in structure_ids and biome_raw in biome_ids:
            edges.append({'structure': structure_id, 'biome': biome_raw})
    run_batch(session, _STRUCTURE_BIOMES_QUERY, edges)
    print(f"  {len(edges)} structure->biome edges")


def export_dimension_biomes(session, dim_ids: set[str], biome_ids: set[str]) -> None:
    edges = []
    for row in read_pipe('dimension_biomes.txt'):
        if len(row) < 2:
            continue
        dim_id, biome_raw = row[0], row[1]
        if dim_id not in dim_ids:
            continue
        biome_id = resolve_biome(biome_raw, biome_ids)
        if biome_id:
            edges.append({'dimension': dim_id, 'biome': biome_id})
    run_batch(session, _DIMENSION_BIOMES_QUERY, edges)
    print(f"  {len(edges)} biome->dimension edges")


def export_structure_spawns(session, structure_ids: set[str], entity_ids: set[str]) -> None:
    edges = []
    for row in read_pipe('structure_spawns.txt'):
        if len(row) < 2:
            continue
        structure_id, entity_id = row[0], row[1]
        if structure_id in structure_ids and entity_id in entity_ids:
            edges.append({'structure': structure_id, 'entity': entity_id})
    run_batch(session, _STRUCTURE_SPAWNS_QUERY, edges)
    print(f"  {len(edges)} entity->structure edges")


def export_block_drops(session, item_ids: set[str]) -> None:
    block_ids = read_ids('blocks.txt')
    loot_entries, blocks_with_loot = _load_block_loot_entries(block_ids, item_ids)
    block_node_rows = node_rows(blocks_with_loot)
    run_batch(session, _MERGE_BLOCKS_QUERY, block_node_rows)
    run_batch(session, _BLOCK_DROPS_QUERY, loot_entries)
    print(f"  {len(block_node_rows)} blocks, {len(loot_entries)} block->item drop edges")


def _load_block_loot_entries(block_ids: set[str], item_ids: set[str]) -> tuple[list[dict], set[str]]:
    loot_entries = []
    blocks_with_loot: set[str] = set()
    for row in read_pipe('loot_blocks.txt'):
        if len(row) < 5:
            continue
        loot_path, item_id = row[0], row[1]
        block_id = loot_path_to_block_id(loot_path)
        if not block_id or block_id not in block_ids or item_id not in item_ids:
            continue
        blocks_with_loot.add(block_id)
        loot_entries.append({'block': block_id, 'item': item_id,
                             'min': parse_kv(row[2]), 'max': parse_kv(row[3]),
                             'weight': parse_kv(row[4])})
    return loot_entries, blocks_with_loot
