"""
merge_biome_parameters.py
=========================
Reads worldgen_data/*.json and deduplicates parameter entries per biome.
Exact duplicates (same biome + identical 6-axis parameters) are removed with
a warning — they almost certainly indicate a generation error upstream.

Writes one file per namespace to worldgen_data/merged/<namespace>.json,
preserving the same structure as the source files.

Usage:
    python pythonScripts/worldgen/merge_biome_parameters.py
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

WORLDGEN_DATA_DIR = Path('worldgen_data')
MERGED_OUTPUT_DIR = WORLDGEN_DATA_DIR / 'merged'
PARAMETER_AXES    = ['temperature', 'humidity', 'continentalness', 'erosion', 'depth', 'weirdness']
END_SECTIONS      = {'highlands', 'midlands', 'edge', 'islands'}


def _params_key(parameters: dict) -> str:
    return json.dumps({ax: parameters[ax] for ax in PARAMETER_AXES}, sort_keys=True)


def dedup_section(entries: list[dict]) -> list[dict]:
    seen:       set[tuple]  = set()
    result:     list[dict]  = []
    duplicates: list[str]   = []

    for entry in entries:
        key = (entry['biome'], _params_key(entry['parameters']))
        if key in seen:
            duplicates.append(entry['biome'])
        else:
            seen.add(key)
            result.append({'biome': entry['biome'], 'parameters': entry['parameters']})

    if duplicates:
        print(f'  WARNING: {len(duplicates)} exact duplicate(s) removed (likely a generation error):')
        for biome in sorted(set(duplicates)):
            count = duplicates.count(biome)
            print(f'    - {biome}  ({count}x)')

    return result


def dedup_end_section(entries: list[dict]) -> list[dict]:
    """Deduplicate End entries by biome ID; keep highest weight on collision."""
    by_biome: dict[str, int] = {}
    for entry in entries:
        biome  = entry['biome']
        weight = entry.get('weight', 1)
        by_biome[biome] = max(by_biome.get(biome, 0), weight)
    return [{'biome': b, 'weight': w} for b, w in sorted(by_biome.items())]


def process_namespace_file(source_path: Path) -> tuple[str, dict] | None:
    data = json.loads(source_path.read_text(encoding='utf-8'))
    if not isinstance(data, dict) or 'namespace' not in data:
        return None
    namespace = data['namespace']
    output    = {'namespace': namespace, 'dimensions': {}}

    for dimension_id, sections in data.get('dimensions', {}).items():
        output['dimensions'][dimension_id] = {}
        for section_name, entries in sections.items():
            if section_name in END_SECTIONS:
                deduped = dedup_end_section(entries)
            else:
                deduped = dedup_section(entries)
            output['dimensions'][dimension_id][section_name] = deduped
            if deduped:
                print(f'  {namespace}  {dimension_id}  {section_name}: {len(entries)} -> {len(deduped)} entries')

    return namespace, output


def main() -> None:
    source_files = sorted(WORLDGEN_DATA_DIR.glob('*.json'))
    if not source_files:
        print(f'No JSON files found in {WORLDGEN_DATA_DIR}/')
        raise SystemExit(1)

    MERGED_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    written = 0
    for source_path in source_files:
        result = process_namespace_file(source_path)
        if result is None:
            print(f'  Skipping {source_path.name} (not a namespace file)')
            continue
        namespace, output = result
        destination = MERGED_OUTPUT_DIR / source_path.name
        destination.write_text(json.dumps(output, indent=2), encoding='utf-8')
        written += 1

    print(f'\nWritten {written} file(s) to {MERGED_OUTPUT_DIR}/')


main()
