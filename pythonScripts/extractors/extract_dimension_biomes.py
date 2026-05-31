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

TAG_TO_DIM = {
    'minecraft:is_overworld': 'minecraft:overworld',
    'minecraft:is_nether':    'minecraft:the_nether',
    'minecraft:is_end':       'minecraft:the_end',
}


def _extract_biomes_from_source(biome_source: dict, parameter_lists: dict) -> list[str]:
    """Extract biome IDs from a biome_source dict. Returns [] if unresolvable."""
    source_type = biome_source.get('type', '')

    if source_type == 'minecraft:the_end':
        return ['minecraft:the_end']

    if source_type == 'minecraft:fixed':
        biome = biome_source.get('biome')
        return [biome] if biome else []

    if source_type == 'minecraft:checkerboard':
        return list(biome_source.get('biomes', []))

    if source_type == 'minecraft:multi_noise':
        if 'biomes' in biome_source:
            return [entry['biome'] for entry in biome_source['biomes'] if 'biome' in entry]
        preset = biome_source.get('preset')
        if preset:
            return parameter_lists.get(preset, [])

    return []


def _load_parameter_lists() -> dict[str, list[str]]:
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
                    relative  = parts[2]
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
    return parameter_lists


def _extract_from_biome_tags() -> tuple[list[str], set[tuple[str, str]]]:
    tag_map = load_tag_map(LISTS)
    rows: list[str] = []
    seen: set[tuple[str, str]] = set()
    for tag_id, dim_id in TAG_TO_DIM.items():
        for biome in sorted(resolve_tag(tag_id, tag_map)):
            pair = (dim_id, biome)
            if pair not in seen:
                seen.add(pair)
                rows.append(f'{dim_id} | {biome}')
    print(f'Phase 1 (biome tags)  : {len(rows)} pairs')
    return rows, seen


def _extract_from_dimension_jsons(
    parameter_lists: dict,
    seen: set[tuple[str, str]],
) -> tuple[list[str], dict[str, int]]:
    new_rows: list[str] = []
    skipped_types: dict[str, int] = defaultdict(int)

    for jar_path in iter_jars(MODS_DIR, extra_jars=[VANILLA_JAR]):
        try:
            with zipfile.ZipFile(jar_path, 'r') as zip_file:
                for entry in zip_file.namelist():
                    if 'worldgen/dimension/' not in entry or not entry.endswith('.json'):
                        continue
                    parts = entry.split('/', 2)
                    if len(parts) < 3:
                        continue
                    namespace = parts[1]
                    relative  = parts[2]
                    dim_name  = relative.split('/')[-1].removesuffix('.json')
                    dim_id    = f'{namespace}:{dim_name}'

                    if namespace == 'minecraft':  # covered by phase 1
                        continue

                    try:
                        data         = json.loads(zip_file.read(entry))
                        generator    = data.get('generator', {})
                        biome_source = generator.get('biome_source', {})
                        source_type  = biome_source.get('type', 'unknown')
                        biomes       = _extract_biomes_from_source(biome_source, parameter_lists)

                        if not biomes:
                            skipped_types[source_type] += 1
                            continue

                        for biome in biomes:
                            pair = (dim_id, biome)
                            if pair not in seen:
                                seen.add(pair)
                                new_rows.append(f'{dim_id} | {biome}')
                    except Exception:
                        pass
        except (zipfile.BadZipFile, Exception):
            pass

    return new_rows, skipped_types


def _report_phase2(new_rows: list[str], skipped_types: dict, total: int) -> None:
    print(f'Phase 2 (dim JSON)    : {len(new_rows)} new pairs')
    if skipped_types:
        print('  Unresolvable biome source types:')
        for source_type, count in sorted(skipped_types.items()):
            print(f'    {source_type}: {count} dimension(s)')
    print(f'Total pairs           : {total}')


def main() -> None:
    parameter_lists          = _load_parameter_lists()
    rows, seen               = _extract_from_biome_tags()
    new_rows, skipped_types  = _extract_from_dimension_jsons(parameter_lists, seen)
    rows.extend(new_rows)
    _report_phase2(new_rows, skipped_types, len(rows))
    write_txt(LISTS, 'dimension_biomes.txt', [(
        'DIMENSION BIOMES  format: dimension_id | biome_id',
        rows,
    )])
    print('Done.')


main()
