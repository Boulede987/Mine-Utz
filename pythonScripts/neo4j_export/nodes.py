from .db import run_batch
from .readers import parse_id, parse_stat_file, node_rows, read_ids

NODE_LABELS = ("Item", "Entity", "Biome", "Structure", "Dimension", "Recipe", "Block")

_MERGE_ITEMS = """
    UNWIND $rows AS r
    MERGE (i:Item {id: r.id})
    SET i.namespace = r.namespace, i.name = r.name, i.image = r.image
    FOREACH (_ IN CASE WHEN r.is_armor THEN [1] ELSE [] END | SET i:Armor)
    FOREACH (_ IN CASE WHEN r.is_tool  THEN [1] ELSE [] END | SET i:Tool)
    FOREACH (_ IN CASE WHEN r.is_food  THEN [1] ELSE [] END | SET i:Food)
    FOREACH (_ IN CASE WHEN r.is_block THEN [1] ELSE [] END | SET i:Block)
"""

_MERGE_ENTITIES = """
    UNWIND $rows AS r
    MERGE (e:Entity {id: r.id})
    SET e.namespace = r.namespace, e.name = r.name, e.image = r.image
"""

_MERGE_BIOMES = """
    UNWIND $rows AS r
    MERGE (b:Biome {id: r.id})
    SET b.namespace = r.namespace, b.name = r.name
"""

_MERGE_STRUCTURES = """
    UNWIND $rows AS r
    MERGE (st:Structure {id: r.id})
    SET st.namespace = r.namespace, st.name = r.name
"""

_MERGE_DIMENSIONS = """
    UNWIND $rows AS r
    MERGE (d:Dimension {id: r.id})
    SET d.namespace = r.namespace, d.name = r.name
"""


def clear_graph(session) -> None:
    session.run("MATCH (n) DETACH DELETE n")


def create_constraints(session) -> None:
    for label in NODE_LABELS:
        session.run(f"CREATE CONSTRAINT IF NOT EXISTS FOR (n:{label}) REQUIRE n.id IS UNIQUE")


def _build_item_rows(item_ids, texture_map, armor_stats, tool_stats, food_stats, block_ids) -> list[dict]:
    rows = []
    for item_id in item_ids:
        parsed = parse_id(item_id)
        if not parsed:
            continue
        namespace, name = parsed
        rows.append({
            'id':        item_id,
            'namespace': namespace,
            'name':      name,
            'image':     texture_map.get(item_id),
            'is_armor':  item_id in armor_stats,
            'is_tool':   item_id in tool_stats,
            'is_food':   item_id in food_stats,
            'is_block':  item_id in block_ids,
            **armor_stats.get(item_id, {}),
            **tool_stats.get(item_id, {}),
            **food_stats.get(item_id, {}),
        })
    return rows


def _apply_item_stats(session, item_ids, armor_stats, tool_stats, food_stats) -> None:
    for stat_dict in (armor_stats, tool_stats, food_stats):
        for item_id, stats in stat_dict.items():
            if item_id in item_ids:
                session.run("MATCH (i:Item {id:$id}) SET i += $p", id=item_id, p=stats)


def export_items(session, item_ids: set[str], texture_map: dict[str, str]) -> None:
    armor_stats = parse_stat_file('armor_stats.txt', ['armor', 'toughness', 'kbRes', 'durability'])
    tool_stats  = parse_stat_file('tool_stats.txt',  ['atkDmg', 'atkSpd', 'durability'])
    food_stats  = parse_stat_file('food_stats.txt',  ['nutrition', 'saturation'])
    block_ids   = read_ids('blocks.txt') & item_ids

    item_rows = _build_item_rows(item_ids, texture_map, armor_stats, tool_stats, food_stats, block_ids)
    run_batch(session, _MERGE_ITEMS, item_rows)
    _apply_item_stats(session, item_ids, armor_stats, tool_stats, food_stats)

    armor_count = sum(1 for row in item_rows if row['is_armor'])
    tool_count  = sum(1 for row in item_rows if row['is_tool'])
    food_count  = sum(1 for row in item_rows if row['is_food'])
    block_count = sum(1 for row in item_rows if row['is_block'])
    print(f"  {len(item_rows)} items ({armor_count} armor, {tool_count} tools, {food_count} food, {block_count} blocks)")


def export_entities(session, entity_ids: set[str], texture_map: dict[str, str]) -> None:
    entity_rows = []
    for entity_id in entity_ids:
        parsed = parse_id(entity_id)
        if parsed:
            namespace, name = parsed
            entity_rows.append({'id': entity_id, 'namespace': namespace, 'name': name,
                                'image': texture_map.get(entity_id)})
    run_batch(session, _MERGE_ENTITIES, entity_rows)
    print(f"  {len(entity_rows)} entities")


def export_biomes(session, biome_ids: set[str]) -> None:
    rows = node_rows(biome_ids)
    run_batch(session, _MERGE_BIOMES, rows)
    print(f"  {len(rows)} biomes")


def export_structures(session, structure_ids: set[str]) -> None:
    rows = node_rows(structure_ids)
    run_batch(session, _MERGE_STRUCTURES, rows)
    print(f"  {len(rows)} structures")


def export_dimensions(session, dim_ids: set[str]) -> None:
    rows = node_rows(dim_ids)
    run_batch(session, _MERGE_DIMENSIONS, rows)
    print(f"  {len(rows)} dimensions")
