"""
sql_gen/07_mc_relations.py
===========================
Generates SQL inserts for spawn and structure-biome relations — vanilla 1.21.1.

Sources
-------
  lists/biome_spawns.txt     — biome | entity | weight | min | max
  lists/structure_biomes.txt — structure | biome_or_tag
  lists/biome_tags.txt       — tag_id | biome_or_subtag  (from extract_biome_tags.py)

BiomeSpawnTable
  Rows where both biome and entity are minecraft: namespace.

StructureBiome
  Vanilla structure->biome rows reference biome tags (#minecraft:has_structure/...).
  Tags are resolved recursively via biome_tags.txt.
  Only rows where the resolved biome exists in the minecraft: Biome table are inserted.

Output: BDD/Scripts/inserts/07_mc_relations.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/07_mc_relations.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_pipe_file, load_registry
from lib.tag_resolver import load_tag_map, resolve_tag

OUTPUT = SQL_INSERTS / '07_mc_relations.sql'


def parse_pipe_row(raw_line: str) -> list[str]:
    return [p.strip() for p in raw_line.split('|')]


def parse_int_field(field: str) -> int:
    return int(field.split(':', 1)[1]) if ':' in field else 1


# ── Load supporting data ──────────────────────────────────────────────────────

mc_biomes = load_registry(LISTS, 'biomes.txt')   # for FK validation
tag_map   = load_tag_map(LISTS)

# ── BiomeSpawnTable ───────────────────────────────────────────────────────────

spawn_rows: list[tuple] = []
seen_spawns: set[tuple] = set()

for line in load_pipe_file(LISTS, 'biome_spawns.txt'):
    parts = parse_pipe_row(line)
    if len(parts) < 5:
        continue
    biome, entity = parts[0], parts[1]
    if not biome.startswith('minecraft:') or not entity.startswith('minecraft:'):
        continue

    biome_ns,  biome_name  = biome.split(':', 1)
    entity_ns, entity_name = entity.split(':', 1)
    weight  = parse_int_field(parts[2])
    min_cnt = parse_int_field(parts[3])
    max_cnt = parse_int_field(parts[4])

    pk = (biome_ns, biome_name, entity_ns, entity_name)
    if pk not in seen_spawns:
        seen_spawns.add(pk)
        spawn_rows.append((biome_ns, biome_name, entity_ns, entity_name, weight, min_cnt, max_cnt))

print(f'BiomeSpawnTable entries : {len(spawn_rows)}')

# ── StructureBiome ────────────────────────────────────────────────────────────

struct_biome_rows: list[tuple] = []
seen_struct_biomes: set[tuple] = set()
skipped_unresolved = 0

for line in load_pipe_file(LISTS, 'structure_biomes.txt'):
    parts = parse_pipe_row(line)
    if len(parts) < 2:
        continue
    structure, biome_or_tag = parts[0], parts[1]
    if not structure.startswith('minecraft:'):
        continue

    struct_ns, struct_name = structure.split(':', 1)

    # Resolve tags to concrete biome IDs
    if biome_or_tag.startswith('#'):
        resolved_biomes = resolve_tag(biome_or_tag[1:], tag_map)
    else:
        resolved_biomes = {biome_or_tag}

    for biome in sorted(resolved_biomes):
        if not biome.startswith('minecraft:') or biome not in mc_biomes:
            skipped_unresolved += 1
            continue   # only vanilla biomes exist in our Biome table
        biome_ns, biome_name = biome.split(':', 1)
        pk = (struct_ns, struct_name, biome_ns, biome_name)
        if pk not in seen_struct_biomes:
            seen_struct_biomes.add(pk)
            struct_biome_rows.append(pk)

print(f'StructureBiome entries  : {len(struct_biome_rows)}')
print(f'  skipped (non-vanilla biomes from tag expansion): {skipped_unresolved}')

# ── Write ─────────────────────────────────────────────────────────────────────

write_sql(OUTPUT, '07 - Minecraft relations (biome spawns + structure biomes)', [
    ('BiomeSpawnTable — entity spawn weights per biome',
     insert_rows(
         'BiomeSpawnTable',
         ['biome_namespace', 'biome_name',
          'entity_namespace', 'entity_name',
          'weight', 'minCount', 'maxCount'],
         spawn_rows,
     )),

    ('StructureBiome — resolved from biome tags via biome_tags.txt',
     insert_rows(
         'StructureBiome',
         ['structure_namespace', 'structure_name', 'biome_namespace', 'biome_name'],
         struct_biome_rows,
     )),
])
