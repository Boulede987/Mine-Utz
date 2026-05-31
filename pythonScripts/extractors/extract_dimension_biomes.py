"""
extract_dimension_biomes.py
============================
Produces lists/dimension_biomes.txt — one row per (dimension, biome) pair.

Two-phase extraction:

Phase 1 — biome tags (vanilla + modded biomes in vanilla dimensions)
  biome_tags.txt already exists.  Three tags map directly to dimensions:
    minecraft:is_overworld -> minecraft:overworld
    minecraft:is_nether    -> minecraft:the_nether
    minecraft:is_end       -> minecraft:the_end
  Mod biomes in vanilla dimensions are included because mods append to these tags.

Phase 2 — dimension JSON files (modded dimensions only)
  Scans data/<ns>/worldgen/dimension/<name>.json in all mod jars.
  Parses biome_source:
    minecraft:multi_noise  with inline 'biomes' list  -> extract biome IDs
    minecraft:multi_noise  with 'preset'              -> follow to parameter list file
    minecraft:the_end                                 -> minecraft:the_end
    minecraft:fixed                                   -> single biome field
    anything else                                     -> skipped (logged)

Output: lists/dimension_biomes.txt
Format: dimension_id | biome_id

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_dimension_biomes.py
"""

import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths        import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils     import write_txt
from lib.tag_resolver import load_tag_map, resolve_tag
from lib.jar_scanner  import iter_jars

# ── Phase 1: biome tags ───────────────────────────────────────────────────────

TAG_TO_DIM = {
    'minecraft:is_overworld': 'minecraft:overworld',
    'minecraft:is_nether':    'minecraft:the_nether',
    'minecraft:is_end':       'minecraft:the_end',
}

tag_map = load_tag_map(LISTS)   # biome_tags.txt
rows: list[str] = []
seen: set[tuple[str, str]] = set()

for tag_id, dim_id in TAG_TO_DIM.items():
    biomes = resolve_tag(tag_id, tag_map)
    for biome in sorted(biomes):
        pair = (dim_id, biome)
        if pair not in seen:
            seen.add(pair)
            rows.append(f'{dim_id} | {biome}')

print(f'Phase 1 (biome tags)  : {len(rows)} pairs')
phase1_count = len(rows)

# ── Phase 2: mod dimension JSON files ────────────────────────────────────────

# Pre-load all multi_noise parameter list files across all jars
# key: "namespace:name" -> list of biome IDs
parameter_lists: dict[str, list[str]] = {}

for jar_path in iter_jars(MODS_DIR, extra_jars=[VANILLA_JAR]):
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            for entry in zip_file.namelist():
                if 'worldgen/multi_noise_biome_source_parameter_list/' not in entry:
                    continue
                if not entry.endswith('.json'):
                    continue
                parts = entry.split('/', 2)
                if len(parts) < 3:
                    continue
                namespace = parts[1]
                relative  = parts[2]  # worldgen/multi_noise_biome_source_parameter_list/<name>.json
                name      = relative.split('/')[-1].removesuffix('.json')
                key       = f'{namespace}:{name}'
                try:
                    data   = json.loads(zip_file.read(entry))
                    biomes = [b['biome'] for b in data.get('biomes', []) if 'biome' in b]
                    if biomes:
                        parameter_lists.setdefault(key, []).extend(biomes)
                except Exception:
                    pass
    except (zipfile.BadZipFile, Exception):
        pass


def biomes_from_source(biome_source: dict, zip_file: zipfile.ZipFile) -> list[str]:
    """Extract biome IDs from a biome_source dict. Returns [] if unresolvable."""
    src_type = biome_source.get('type', '')

    if src_type == 'minecraft:the_end':
        return ['minecraft:the_end']

    if src_type == 'minecraft:fixed':
        b = biome_source.get('biome')
        return [b] if b else []

    if src_type == 'minecraft:checkerboard':
        return list(biome_source.get('biomes', []))

    if src_type == 'minecraft:multi_noise':
        # Inline list
        if 'biomes' in biome_source:
            return [b['biome'] for b in biome_source['biomes'] if 'biome' in b]
        # Preset reference
        preset = biome_source.get('preset')
        if preset:
            return parameter_lists.get(preset, [])

    return []


skipped_types: dict[str, int] = defaultdict(int)
phase2_new = 0

for jar_path in iter_jars(MODS_DIR, extra_jars=[VANILLA_JAR]):
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            for entry in zip_file.namelist():
                if 'worldgen/dimension/' not in entry:
                    continue
                if not entry.endswith('.json'):
                    continue
                parts = entry.split('/', 2)
                if len(parts) < 3:
                    continue
                namespace = parts[1]
                relative  = parts[2]   # worldgen/dimension/<name>.json
                dim_name  = relative.split('/')[-1].removesuffix('.json')
                dim_id    = f'{namespace}:{dim_name}'

                # Skip vanilla dimensions — covered by phase 1
                if namespace == 'minecraft':
                    continue

                try:
                    data          = json.loads(zip_file.read(entry))
                    generator     = data.get('generator', {})
                    biome_source  = generator.get('biome_source', {})
                    source_type   = biome_source.get('type', 'unknown')
                    biomes        = biomes_from_source(biome_source, zip_file)

                    if not biomes:
                        skipped_types[source_type] += 1
                        continue

                    for biome in biomes:
                        pair = (dim_id, biome)
                        if pair not in seen:
                            seen.add(pair)
                            rows.append(f'{dim_id} | {biome}')
                            phase2_new += 1
                except Exception:
                    pass
    except (zipfile.BadZipFile, Exception):
        pass

print(f'Phase 2 (dim JSON)    : {phase2_new} new pairs')
if skipped_types:
    print('  Unresolvable biome source types:')
    for t, count in sorted(skipped_types.items()):
        print(f'    {t}: {count} dimension(s)')
print(f'Total pairs           : {len(rows)}')

# ── Write ─────────────────────────────────────────────────────────────────────

write_txt(LISTS, 'dimension_biomes.txt', [(
    'DIMENSION BIOMES  format: dimension_id | biome_id',
    rows,
)])
print('Done.')
