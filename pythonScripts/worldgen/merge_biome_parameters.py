"""
merge_biome_parameters.py
=========================
Reads worldgen_data/*.json and collapses all parameter entries for each biome
into one merged entry (min-of-mins, max-of-maxes per axis).

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


def merge_parameter_entries(parameter_entries: list[dict]) -> dict:
    merged = {}
    for axis in PARAMETER_AXES:
        all_values  = [entry[axis] for entry in parameter_entries]
        merged[axis] = [
            min(values[0] for values in all_values),
            max(values[1] for values in all_values),
        ]
    merged['offset'] = parameter_entries[0].get('offset', 0.0)
    return merged


def merge_section(entries: list[dict]) -> list[dict]:
    by_biome: dict[str, list[dict]] = {}
    for entry in entries:
        by_biome.setdefault(entry['biome'], []).append(entry['parameters'])

    merged_entries = []
    for biome_id, parameter_list in sorted(by_biome.items()):
        merged_entries.append({
            'biome':      biome_id,
            'parameters': merge_parameter_entries(parameter_list),
            'merged_from': len(parameter_list),
        })
    return merged_entries


def merge_end_section(entries: list[dict]) -> list[dict]:
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
    output    = {'namespace': namespace, 'note': 'Parameters merged: one entry per biome (min-of-mins, max-of-maxes).', 'dimensions': {}}

    for dimension_id, sections in data.get('dimensions', {}).items():
        output['dimensions'][dimension_id] = {}
        for section_name, entries in sections.items():
            if section_name in END_SECTIONS:
                merged = merge_end_section(entries)
            else:
                merged = merge_section(entries)
            output['dimensions'][dimension_id][section_name] = merged
            if merged:
                print(f'  {namespace}  {dimension_id}  {section_name}: {len(entries)} -> {len(merged)} entries')

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
