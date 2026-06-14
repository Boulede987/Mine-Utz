"""
parse_bwg_source.py
Simulates BWGTerraBlenderRegion (regions 1, 2, 3) to produce
noise parameter entries for Oh The Biomes We've Gone biomes.

Output: worldgen_data/biomeswevegone.json
Run from repo root: python pythonScripts/worldgen/parse_bwg_source.py
"""

import json
from pathlib import Path

from bwg.regions import ALL_REGIONS

OUTPUT_DIR = Path('worldgen_data')
NAMESPACE  = 'biomeswevegone:'


def depth_section(depth: list) -> str:
    depth_min, depth_max = depth[0], depth[1]
    if depth_max <= 0.05: return 'surface'
    if depth_min >= 1.05: return 'deep'
    if depth_min >= 0.15: return 'caves'
    return 'underground'


def collect_all_entries() -> list:
    entries = []
    for region in ALL_REGIONS:
        entries.extend(region.run())
    return entries


def entry_dedup_key(entry: dict) -> tuple:
    params = entry['parameters']
    return (
        entry['biome'],
        tuple(params['temperature']), tuple(params['humidity']),
        tuple(params['continentalness']), tuple(params['erosion']),
        tuple(params['depth']), tuple(params['weirdness']), params['offset'],
    )


def filter_to_namespace(entries: list, namespace: str) -> list:
    seen = set()
    result = []
    for entry in entries:
        if not entry['biome'].startswith(namespace):
            continue
        key = entry_dedup_key(entry)
        if key not in seen:
            seen.add(key)
            result.append(entry)
    return result


def group_by_section(entries: list) -> dict:
    sections = {'surface': [], 'underground': [], 'caves': [], 'deep': []}
    for entry in entries:
        sections[depth_section(entry['parameters']['depth'])].append(entry)
    for section_entries in sections.values():
        section_entries.sort(key=lambda entry: entry['biome'])
    return sections


def write_output(sections: dict, namespace_label: str) -> None:
    OUTPUT_DIR.mkdir(exist_ok=True)
    path = OUTPUT_DIR / f'{namespace_label}.json'

    # Merge with existing file so nether/other dims from KubeJS dump are preserved
    if path.exists():
        existing = json.loads(path.read_text(encoding='utf-8'))
        dims = existing.get('dimensions', {})
    else:
        dims = {}

    dims['minecraft:overworld'] = sections
    output = {'namespace': namespace_label, 'dimensions': dims}

    totals       = {key: len(entries) for key, entries in sections.items()}
    unique_count = len({entry['biome'] for entries in sections.values() for entry in entries})
    print(f"Oh The Biomes We've Gone: {unique_count} unique biomes, {sum(totals.values())} total entries")
    print(f"  {totals}")
    path.write_text(json.dumps(output, indent=2), encoding='utf-8')
    print(f"Written -> {path}")


def main():
    all_entries = collect_all_entries()
    bwg_entries = filter_to_namespace(all_entries, NAMESPACE)
    sections    = group_by_section(bwg_entries)
    write_output(sections, 'biomeswevegone')


main()
