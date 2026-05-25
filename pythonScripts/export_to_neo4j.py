"""
export_to_neo4j.py
==================
Export Mineutze modpack data to a Neo4j graph database.

Reads: lists/*.txt  (no MariaDB needed)
Writes: Neo4j at bolt://localhost:7687

Requirements:
    pip install neo4j

Setup:
    1. Install Neo4j Desktop (https://neo4j.com/download/) or Community Server
    2. Create/start a database, set a password
    3. Update NEO4J_PASSWORD below (and URI/USER if different)
    4. Run: python pythonScripts/export_to_neo4j.py

Graph model
-----------
Nodes : Item, Entity, Biome, Structure, Dimension, Recipe, Block
        Item subtypes carry extra labels: :Armor :Tool :Food
Edges :
  (Recipe)-[:PRODUCES {count}]->(Item)
  (Recipe)-[:REQUIRES]->(Item)          -- ingredients
  (Recipe)-[:USES_STATION]->(Item)
  (Entity)-[:DROPS {min,max,weight}]->(Item)
  (Block)-[:DROPS {min,max,weight}]->(Item)   -- mining drops
  (Structure)-[:CONTAINS {min,max,weight}]->(Item)   -- chest loot
  (Entity)-[:SPAWNS_IN {weight,min,max}]->(Biome)
  (Entity)-[:FOUND_IN_STRUCTURE]->(Structure)
  (Structure)-[:FOUND_IN]->(Biome)
  (Biome)-[:IN_DIMENSION]->(Dimension)
"""

import base64
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
from lib.paths import LISTS, ROOT

WEBUI_TEXTURES = ROOT / 'webui' / 'textures'

try:
    from neo4j import GraphDatabase
except ImportError:
    print("ERROR: neo4j driver not installed.")
    print("       Run: pip install neo4j")
    sys.exit(1)

# ── Config ──────────────────────────────────────────────────────────────────

NEO4J_URI      = "bolt://localhost:7687"
NEO4J_USER     = "neo4j"
NEO4J_PASSWORD = "password"   # <── change this

BATCH_SIZE = 500

# ── File helpers ─────────────────────────────────────────────────────────────

def read_ids(filename: str) -> set[str]:
    path = LISTS / filename
    if not path.exists():
        return set()
    ids = set()
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('=') and not line.startswith('#'):
            ids.add(line)
    return ids


def read_pipe(filename: str) -> list[list[str]]:
    path = LISTS / filename
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('=') or line.startswith('#'):
            continue
        rows.append([p.strip() for p in line.split('|')])
    return rows


def parse_id(raw: str) -> tuple[str, str] | None:
    if ':' not in raw:
        return None
    ns, _, name = raw.partition(':')
    return (ns.strip(), name.strip()) if ns and name else None


def resolve_biome(raw: str, known: set[str]) -> str | None:
    """Bare name ('badlands') → try 'minecraft:{raw}', then any unique match."""
    if ':' in raw:
        return raw if raw in known else None
    candidate = f"minecraft:{raw}"
    if candidate in known:
        return candidate
    matches = [b for b in known if b.endswith(f":{raw}")]
    return matches[0] if len(matches) == 1 else None


def parse_kv(raw: str) -> int:
    """Parse 'weight:7' → 7. Fallback 1."""
    try:
        return int(raw.split(':', 1)[1])
    except (IndexError, ValueError):
        return 1


# ── Batching ─────────────────────────────────────────────────────────────────

def run_batch(session, query: str, rows: list[dict]):
    for i in range(0, len(rows), BATCH_SIZE):
        session.run(query, rows=rows[i:i + BATCH_SIZE])


# ── Texture export ───────────────────────────────────────────────────────────

def export_textures() -> dict[str, str]:
    """Save entity + item textures as PNG files in webui/textures/. Returns {id: filename}."""
    WEBUI_TEXTURES.mkdir(parents=True, exist_ok=True)
    mapping: dict[str, str] = {}

    def load_tex_file(filename: str, label: str):
        path = LISTS / filename
        if not path.exists():
            print(f"  {filename} not found — skipping")
            return
        count = 0
        for line in path.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if not line or line.startswith('=') or line.startswith('#'):
                continue
            parts = [p.strip() for p in line.split('|', 1)]
            if len(parts) != 2:
                continue
            item_id, b64 = parts
            safe_name = item_id.replace(':', '__') + '.png'
            out_path = WEBUI_TEXTURES / safe_name
            try:
                out_path.write_bytes(base64.b64decode(b64))
                mapping[item_id] = safe_name
                count += 1
            except Exception:
                pass
        print(f"  {count} {label} textures saved")

    load_tex_file('entity_textures.txt', 'entity')
    load_tex_file('item_textures.txt',   'item')
    return mapping


# ── Export ───────────────────────────────────────────────────────────────────

def export(driver):
    with driver.session() as s:

        # ── Textures ───────────────────────────────────────────────
        print("Exporting entity textures...")
        texture_map = export_textures()

        # ── Clear ──────────────────────────────────────────────────
        print("Clearing existing graph...")
        s.run("MATCH (n) DETACH DELETE n")

        # ── Constraints ────────────────────────────────────────────
        print("Creating constraints...")
        for label in ("Item", "Entity", "Biome", "Structure", "Dimension", "Recipe", "Block"):
            s.run(f"CREATE CONSTRAINT IF NOT EXISTS FOR (n:{label}) REQUIRE n.id IS UNIQUE")

        # ── Items ──────────────────────────────────────────────────
        print("Loading items...")
        item_ids = read_ids('items.txt')

        def parse_stat_file(fname: str, fields: list[str]) -> dict[str, dict]:
            out = {}
            for row in read_pipe(fname):
                if len(row) < 1 + len(fields):
                    continue
                try:
                    out[row[0]] = {f: float(row[i + 1]) for i, f in enumerate(fields)}
                except ValueError:
                    pass
            return out

        armor_stats = parse_stat_file('armor_stats.txt', ['armor', 'toughness', 'kbRes', 'durability'])
        tool_stats  = parse_stat_file('tool_stats.txt',  ['atkDmg', 'atkSpd', 'durability'])
        food_stats  = parse_stat_file('food_stats.txt',  ['nutrition', 'saturation'])

        item_rows = []
        for iid in item_ids:
            p = parse_id(iid)
            if not p:
                continue
            row = {'id': iid, 'namespace': p[0], 'name': p[1],
                   'image':    texture_map.get(iid),
                   'is_armor': iid in armor_stats,
                   'is_tool':  iid in tool_stats,
                   'is_food':  iid in food_stats,
                   **armor_stats.get(iid, {}),
                   **tool_stats.get(iid, {}),
                   **food_stats.get(iid, {})}
            item_rows.append(row)

        run_batch(s, """
            UNWIND $rows AS r
            MERGE (i:Item {id: r.id})
            SET i.namespace = r.namespace, i.name = r.name, i.image = r.image
            FOREACH (_ IN CASE WHEN r.is_armor THEN [1] ELSE [] END |
                SET i:Armor)
            FOREACH (_ IN CASE WHEN r.is_tool  THEN [1] ELSE [] END |
                SET i:Tool)
            FOREACH (_ IN CASE WHEN r.is_food  THEN [1] ELSE [] END |
                SET i:Food)
        """, item_rows)
        print(f"  {len(item_rows)} items  "
              f"({sum(1 for r in item_rows if r['is_armor'])} armor, "
              f"{sum(1 for r in item_rows if r['is_tool'])} tools, "
              f"{sum(1 for r in item_rows if r['is_food'])} food)")

        # ── Item stat properties (separate pass — FOREACH SET has limits) ──
        for iid, stats in armor_stats.items():
            if iid in item_ids:
                s.run("MATCH (i:Item {id:$id}) SET i += $p", id=iid, p=stats)
        for iid, stats in tool_stats.items():
            if iid in item_ids:
                s.run("MATCH (i:Item {id:$id}) SET i += $p", id=iid, p=stats)
        for iid, stats in food_stats.items():
            if iid in item_ids:
                s.run("MATCH (i:Item {id:$id}) SET i += $p", id=iid, p=stats)

        # ── Entities ───────────────────────────────────────────────
        print("Loading entities...")
        entity_ids = read_ids('mobs.txt')
        entity_rows = [{'id': e, 'namespace': p[0], 'name': p[1],
                        'image': texture_map.get(e)}
                       for e in entity_ids if (p := parse_id(e))]
        run_batch(s, """
            UNWIND $rows AS r
            MERGE (e:Entity {id: r.id})
            SET e.namespace = r.namespace, e.name = r.name, e.image = r.image
        """, entity_rows)
        print(f"  {len(entity_rows)} entities")

        # ── Biomes ─────────────────────────────────────────────────
        print("Loading biomes...")
        biome_ids = read_ids('biomes.txt')
        biome_rows = [{'id': b, 'namespace': p[0], 'name': p[1]}
                      for b in biome_ids if (p := parse_id(b))]
        run_batch(s, """
            UNWIND $rows AS r
            MERGE (b:Biome {id: r.id})
            SET b.namespace = r.namespace, b.name = r.name
        """, biome_rows)
        print(f"  {len(biome_rows)} biomes")

        # ── Structures ─────────────────────────────────────────────
        print("Loading structures...")
        structure_ids = read_ids('structures.txt')
        structure_rows = [{'id': x, 'namespace': p[0], 'name': p[1]}
                          for x in structure_ids if (p := parse_id(x))]
        run_batch(s, """
            UNWIND $rows AS r
            MERGE (st:Structure {id: r.id})
            SET st.namespace = r.namespace, st.name = r.name
        """, structure_rows)
        print(f"  {len(structure_rows)} structures")

        # ── Dimensions ─────────────────────────────────────────────
        print("Loading dimensions...")
        dim_ids = read_ids('dimensions.txt')
        dim_rows = [{'id': d, 'namespace': p[0], 'name': p[1]}
                    for d in dim_ids if (p := parse_id(d))]
        run_batch(s, """
            UNWIND $rows AS r
            MERGE (d:Dimension {id: r.id})
            SET d.namespace = r.namespace, d.name = r.name
        """, dim_rows)
        print(f"  {len(dim_rows)} dimensions")

        # ── Recipes ────────────────────────────────────────────────
        # Format: id | type | station | result | count | ingredients (comma-sep)
        print("Loading recipes...")
        recipe_nodes, produces, requires, stations = [], [], [], []
        for row in read_pipe('recipes.txt'):
            if len(row) < 5:
                continue
            rid, rtype, station, result, count_raw = row[0], row[1], row[2], row[3], row[4]
            if result not in item_ids:
                continue
            try:
                count = int(count_raw.lstrip('x'))
            except ValueError:
                count = 1

            recipe_nodes.append({'id': rid, 'type': rtype})
            produces.append({'recipe': rid, 'item': result, 'count': count})
            if station in item_ids:
                stations.append({'recipe': rid, 'station': station})
            if len(row) > 5:
                for ing in row[5].split(','):
                    ing = ing.strip()
                    if ing and ing in item_ids:
                        requires.append({'recipe': rid, 'item': ing})

        run_batch(s, "UNWIND $rows AS r MERGE (rc:Recipe {id:r.id}) SET rc.type = r.type",
                  recipe_nodes)
        run_batch(s, """
            UNWIND $rows AS r
            MATCH (rc:Recipe {id:r.recipe}), (i:Item {id:r.item})
            MERGE (rc)-[:PRODUCES {count:r.count}]->(i)
        """, produces)
        run_batch(s, """
            UNWIND $rows AS r
            MATCH (rc:Recipe {id:r.recipe}), (i:Item {id:r.item})
            MERGE (rc)-[:REQUIRES]->(i)
        """, requires)
        run_batch(s, """
            UNWIND $rows AS r
            MATCH (rc:Recipe {id:r.recipe}), (i:Item {id:r.station})
            MERGE (rc)-[:USES_STATION]->(i)
        """, stations)
        print(f"  {len(recipe_nodes)} recipes → "
              f"{len(produces)} outputs, {len(requires)} ingredients, {len(stations)} stations")

        # ── Entity loot ────────────────────────────────────────────
        # entity_loottables.txt: entity_id | loot_table_path
        # loot_entities.txt:     loot_path | item | min | max | weight
        print("Loading entity loot...")
        loot_to_entity: dict[str, str] = {}
        for row in read_pipe('entity_loottables.txt'):
            if len(row) == 2:
                loot_to_entity[row[1]] = row[0]

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

        run_batch(s, """
            UNWIND $rows AS r
            MATCH (e:Entity {id:r.entity}), (i:Item {id:r.item})
            MERGE (e)-[d:DROPS]->(i)
            SET d.min = r.min, d.max = r.max, d.weight = r.weight
        """, drops)
        print(f"  {len(drops)} entity→item drop edges")

        # ── Structure chest loot ───────────────────────────────────
        # structure_loottables.txt: structure_id | block | pos | loot_path
        # loot_chests.txt:          loot_path | item | min | max | weight
        print("Loading structure chest loot...")
        struct_to_loot_paths: dict[str, list[str]] = {}
        for row in read_pipe('structure_loottables.txt'):
            if len(row) >= 4:
                struct_to_loot_paths.setdefault(row[0], []).append(row[3])

        chest_loot: dict[str, list[dict]] = {}
        for row in read_pipe('loot_chests.txt'):
            if len(row) < 5:
                continue
            lp, item_id = row[0], row[1]
            if item_id not in item_ids:
                continue
            chest_loot.setdefault(lp, []).append(
                {'item': item_id, 'min': parse_kv(row[2]),
                 'max': parse_kv(row[3]), 'weight': parse_kv(row[4])})

        contains = []
        for sid, loot_paths in struct_to_loot_paths.items():
            if sid not in structure_ids:
                continue
            seen = set()
            for lp in loot_paths:
                for entry in chest_loot.get(lp, []):
                    key = (sid, entry['item'])
                    if key not in seen:
                        seen.add(key)
                        contains.append({'structure': sid, **entry})

        run_batch(s, """
            UNWIND $rows AS r
            MATCH (st:Structure {id:r.structure}), (i:Item {id:r.item})
            MERGE (st)-[c:CONTAINS]->(i)
            SET c.min = r.min, c.max = r.max, c.weight = r.weight
        """, contains)
        print(f"  {len(contains)} structure→item chest edges")

        # ── Biome spawns ───────────────────────────────────────────
        # biome_spawns.txt: biome | entity | weight | min | max
        print("Loading biome spawns...")
        spawns = []
        for row in read_pipe('biome_spawns.txt'):
            if len(row) < 5:
                continue
            biome_id, entity_id = row[0], row[1]
            if biome_id not in biome_ids or entity_id not in entity_ids:
                continue
            spawns.append({'biome': biome_id, 'entity': entity_id,
                           'weight': parse_kv(row[2]), 'min': parse_kv(row[3]), 'max': parse_kv(row[4])})

        run_batch(s, """
            UNWIND $rows AS r
            MATCH (e:Entity {id:r.entity}), (b:Biome {id:r.biome})
            MERGE (e)-[sp:SPAWNS_IN]->(b)
            SET sp.weight = r.weight, sp.min = r.min, sp.max = r.max
        """, spawns)
        print(f"  {len(spawns)} entity→biome spawn edges")

        # ── Structure biomes ───────────────────────────────────────
        # structure_biomes.txt: structure | biome_or_tag  (skip #tags)
        print("Loading structure biomes...")
        struct_biome = []
        for row in read_pipe('structure_biomes.txt'):
            if len(row) < 2:
                continue
            sid, biome_raw = row[0], row[1]
            if biome_raw.startswith('#'):
                continue
            if sid in structure_ids and biome_raw in biome_ids:
                struct_biome.append({'structure': sid, 'biome': biome_raw})

        run_batch(s, """
            UNWIND $rows AS r
            MATCH (st:Structure {id:r.structure}), (b:Biome {id:r.biome})
            MERGE (st)-[:FOUND_IN]->(b)
        """, struct_biome)
        print(f"  {len(struct_biome)} structure→biome edges")

        # ── Dimension biomes ───────────────────────────────────────
        # dimension_biomes.txt: dimension_id | biome (bare or namespaced)
        print("Loading dimension biomes...")
        dim_biome = []
        for row in read_pipe('dimension_biomes.txt'):
            if len(row) < 2:
                continue
            dim_id, biome_raw = row[0], row[1]
            if dim_id not in dim_ids:
                continue
            biome_id = resolve_biome(biome_raw, biome_ids)
            if biome_id:
                dim_biome.append({'dimension': dim_id, 'biome': biome_id})

        run_batch(s, """
            UNWIND $rows AS r
            MATCH (d:Dimension {id:r.dimension}), (b:Biome {id:r.biome})
            MERGE (b)-[:IN_DIMENSION]->(d)
        """, dim_biome)
        print(f"  {len(dim_biome)} biome→dimension edges")

        # ── Structure spawns ───────────────────────────────────────
        # structure_spawns.txt: structure | entity | weight | min | max
        print("Loading structure spawns...")
        struct_spawns = []
        for row in read_pipe('structure_spawns.txt'):
            if len(row) < 2:
                continue
            sid, eid = row[0], row[1]
            if sid in structure_ids and eid in entity_ids:
                struct_spawns.append({'structure': sid, 'entity': eid})

        run_batch(s, """
            UNWIND $rows AS r
            MATCH (st:Structure {id:r.structure}), (e:Entity {id:r.entity})
            MERGE (e)-[:FOUND_IN_STRUCTURE]->(st)
        """, struct_spawns)
        print(f"  {len(struct_spawns)} entity→structure edges")

        # ── Block mining drops ─────────────────────────────────────
        # loot_blocks.txt: loot_path | item | min | max | weight
        # loot_path format: namespace:blocks/block_name → block ID: namespace:block_name
        print("Loading block mining drops...")
        block_ids = read_ids('blocks.txt')

        def loot_path_to_block_id(source: str) -> str | None:
            if ':' not in source:
                return None
            ns, _, path = source.partition(':')
            if path.startswith('blocks/'):
                return f"{ns}:{path[len('blocks/'):]}"
            return None

        block_nodes_seen: set[str] = set()
        block_drops = []
        for row in read_pipe('loot_blocks.txt'):
            if len(row) < 5:
                continue
            loot_path, item_id = row[0], row[1]
            block_id = loot_path_to_block_id(loot_path)
            if not block_id or block_id not in block_ids or item_id not in item_ids:
                continue
            block_nodes_seen.add(block_id)
            block_drops.append({'block': block_id, 'item': item_id,
                                 'min': parse_kv(row[2]), 'max': parse_kv(row[3]),
                                 'weight': parse_kv(row[4])})

        block_node_rows = [{'id': bid, 'namespace': p[0], 'name': p[1]}
                           for bid in block_nodes_seen if (p := parse_id(bid))]
        run_batch(s, """
            UNWIND $rows AS r
            MERGE (b:Block {id: r.id})
            SET b.namespace = r.namespace, b.name = r.name
        """, block_node_rows)
        run_batch(s, """
            UNWIND $rows AS r
            MATCH (b:Block {id:r.block}), (i:Item {id:r.item})
            MERGE (b)-[d:DROPS]->(i)
            SET d.min = r.min, d.max = r.max, d.weight = r.weight
        """, block_drops)
        print(f"  {len(block_node_rows)} blocks, {len(block_drops)} block→item drop edges")

        print("\nExport complete. Open http://localhost:7474 in your browser.")
        print("Example query:")
        print("  MATCH (i:Item {name:'diamond_sword'})<-[:PRODUCES]-(r:Recipe)-[:REQUIRES]->(ing:Item) RETURN *")


def main():
    print(f"Connecting to Neo4j at {NEO4J_URI}...")
    try:
        driver = GraphDatabase.driver(NEO4J_URI, auth=(NEO4J_USER, NEO4J_PASSWORD))
        driver.verify_connectivity()
        print("Connected.\n")
    except Exception as e:
        print(f"Connection failed: {e}")
        print("Ensure Neo4j is running and NEO4J_PASSWORD is correct.")
        sys.exit(1)

    try:
        export(driver)
    finally:
        driver.close()


if __name__ == "__main__":
    main()
