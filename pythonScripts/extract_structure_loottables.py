"""
extract_structure_loottables.py
================================
Scans all jars for structure data in two phases:

Phase 1 — Build a dynamic template-prefix → registered structure ID mapping
  by reading every worldgen/structure/<id>.json and extracting its start_pool
  (or template) field.  No hardcoded maps.

Phase 2 — Scan structure template .nbt files, resolve each template path to a
  registered structure ID using the map from Phase 1, and extract:
    • block counts per structure (structure_blocks.txt)
    • chest loot table assignments (structure_loottables.txt)

Output files
------------
  lists/structure_loottables.txt   format: structure_id | block_type | x,y,z | loottable
  lists/structure_blocks.txt       format: structure_id | block_name | count

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_structure_loottables.py

Requires:
  pip install nbtlib
"""

import gzip
import io
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import nbtlib

from lib.paths    import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils import write_txt
from lib.jar_scanner import iter_jars

jars = iter_jars(MODS_DIR, extra_jars=[VANILLA_JAR])

# ── Phase 1: Derive template-prefix → structure ID from worldgen/structure JSONs

# structure_id → pool_id  (from start_pool field of jigsaw structures)
struct_start_pools: dict[str, str] = {}
# template_path → structure_id  (from direct `template` field of single-piece structures)
direct_template_map: dict[str, str] = {}

print(f'Phase 1: scanning {len(jars)} jar(s) for worldgen/structure JSONs...')

for jar_path in jars:
    try:
        with zipfile.ZipFile(jar_path, 'r') as zf:
            for entry in zf.namelist():
                if not entry.startswith('data/') or not entry.endswith('.json'):
                    continue
                parts = entry.split('/', 2)
                if len(parts) < 3:
                    continue
                namespace, relative = parts[1], parts[2]
                if not relative.startswith('worldgen/structure/'):
                    continue
                struct_name = relative[len('worldgen/structure/'):].removesuffix('.json')
                struct_id   = f'{namespace}:{struct_name}'
                if struct_id in struct_start_pools or struct_id in direct_template_map.values():
                    continue
                try:
                    data = json.loads(zf.read(entry))
                    pool = data.get('start_pool', '')
                    if isinstance(pool, str) and pool:
                        struct_start_pools[struct_id] = pool
                        continue
                    # Fallback: single-piece structure with direct template reference
                    tpl = data.get('template', '')
                    if isinstance(tpl, str) and tpl:
                        direct_template_map[tpl] = struct_id
                except Exception:
                    pass
    except (zipfile.BadZipFile, Exception):
        pass

print(f'  Structures with start_pool   : {len(struct_start_pools)}')
print(f'  Structures with direct tpl   : {len(direct_template_map)}')

# Build pool_prefix → {structure_ids} — one entry per (prefix, structure) pair
# e.g. "minecraft:bastion/starts" → prefix "minecraft:bastion" → bastion_remnant
pool_prefix_candidates: dict[str, set] = defaultdict(set)
for struct_id, pool_id in struct_start_pools.items():
    if ':' not in pool_id:
        continue
    ns, path = pool_id.split(':', 1)
    segments = path.split('/')
    for n in range(1, len(segments) + 1):
        prefix = f"{ns}:{'/'.join(segments[:n])}"
        pool_prefix_candidates[prefix].add(struct_id)

# Keep only unambiguous prefixes (exactly one structure per prefix)
pool_prefix_to_struct: dict[str, str] = {
    prefix: next(iter(structs))
    for prefix, structs in pool_prefix_candidates.items()
    if len(structs) == 1
}

print(f'  Unambiguous template prefixes: {len(pool_prefix_to_struct)}')


def resolve_template(template_id: str) -> str | None:
    """Map a template path ID to a registered structure ID, or None if unknown.

    Tries progressively longer path prefixes until an unambiguous match is found.
    Example: "minecraft:village/plains/houses/foo"
      → try "minecraft:village"       (ambiguous — skip)
      → try "minecraft:village/plains" (unambiguous — return "minecraft:village_plains")
    """
    # Direct single-piece template
    if template_id in direct_template_map:
        return direct_template_map[template_id]
    if ':' not in template_id:
        return None
    ns, path = template_id.split(':', 1)
    segments = path.split('/')
    for n in range(1, len(segments) + 1):
        prefix = f"{ns}:{'/'.join(segments[:n])}"
        if prefix in pool_prefix_to_struct:
            return pool_prefix_to_struct[prefix]
    return None


# ── Phase 2: Scan jars for structure NBT files ────────────────────────────────

result_rows      = []  # structure_loottables.txt
block_count_rows = []  # structure_blocks.txt
entity_rows_seen: set[tuple[str, str]] = set()
entity_rows      = []  # structure_nbt_entities.txt

_IGNORE_BLOCKS = {
    'minecraft:air', 'minecraft:cave_air',
    'minecraft:void_air', 'minecraft:structure_void',
}

print(f'\nPhase 2: scanning {len(jars)} jar(s) for structure NBT files...\n')

unresolved: set[str] = set()

for jar_path in jars:
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            for entry_name in zip_file.namelist():
                if not entry_name.startswith('data/') or not entry_name.endswith('.nbt'):
                    continue
                parts = entry_name.split('/', 2)
                if len(parts) < 3:
                    continue
                namespace, relative = parts[1], parts[2]

                if relative.startswith('structures/'):
                    prefix_len = len('structures/')
                elif relative.startswith('structure/'):
                    prefix_len = len('structure/')
                else:
                    continue

                template_name = relative[prefix_len:].removesuffix('.nbt')
                template_id   = f'{namespace}:{template_name}'

                structure_id = resolve_template(template_id)
                if not structure_id:
                    unresolved.add(f'{namespace}:{template_name.split("/")[0]}')
                    continue

                try:
                    raw_bytes = zip_file.read(entry_name)
                    try:
                        raw_bytes = gzip.decompress(raw_bytes)
                    except Exception:
                        pass

                    nbt_data = nbtlib.File.parse(io.BytesIO(raw_bytes))
                    root     = nbt_data.get('') or nbt_data
                    palette  = root.get('palette', [])
                    blocks   = root.get('blocks', [])

                    palette_map = {
                        i: str(e.get('Name', ''))
                        for i, e in enumerate(palette)
                    }

                    # Block counts for this template
                    counts: dict[str, int] = {}
                    for block in blocks:
                        state_idx  = int(block.get('state', 0))
                        block_name = palette_map.get(state_idx, '')
                        if block_name and block_name not in _IGNORE_BLOCKS:
                            counts[block_name] = counts.get(block_name, 0) + 1
                    for block_name, count in sorted(counts.items()):
                        block_count_rows.append(f'{structure_id} | {block_name} | {count}')

                    # Entities placed in template
                    for entity in root.get('entities', []):
                        entity_nbt = entity.get('nbt', {})
                        entity_id  = str(entity_nbt.get('id', ''))
                        if entity_id and ':' in entity_id:
                            pair = (structure_id, entity_id)
                            if pair not in entity_rows_seen:
                                entity_rows_seen.add(pair)
                                entity_rows.append(f'{structure_id} | {entity_id}')

                    # Chest loot tables
                    for block in blocks:
                        block_nbt  = block.get('nbt')
                        if not block_nbt:
                            continue
                        loot_table = block_nbt.get('LootTable')
                        if not loot_table:
                            continue
                        loot_str   = str(loot_table)
                        position   = block.get('pos', [0, 0, 0])
                        pos_str    = f'{position[0]},{position[1]},{position[2]}'
                        state_idx  = int(block.get('state', 0))
                        block_name = palette_map.get(state_idx, 'unknown')
                        result_rows.append(
                            f'{structure_id} | {block_name} | {pos_str} | {loot_str}'
                        )

                except Exception as parse_error:
                    print(f'  !! Could not parse {entry_name} in {jar_path.name}: {parse_error}')

    except zipfile.BadZipFile:
        print(f'  !! Bad zip: {jar_path.name}')
    except Exception as error:
        print(f'  !! Error in {jar_path.name}: {error}')

if unresolved:
    print(f'  Templates with no structure mapping ({len(unresolved)} namespaces): '
          + ', '.join(sorted(unresolved)[:10]))

# ── Write output ──────────────────────────────────────────────────────────────

write_txt(LISTS, 'structure_loottables.txt', [(
    'STRUCTURE CHEST LOOT TABLES  format: structure_id | block_type | position | loottable',
    result_rows,
)])
write_txt(LISTS, 'structure_blocks.txt', [(
    'STRUCTURE BLOCKS  format: structure_id | block_name | count',
    block_count_rows,
)])
write_txt(LISTS, 'structure_nbt_entities.txt', [(
    'STRUCTURE NBT ENTITIES  format: structure_id | entity_id',
    sorted(entity_rows),
)])

print('Done.')
