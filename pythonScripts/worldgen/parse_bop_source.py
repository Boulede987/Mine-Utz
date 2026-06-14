"""
parse_bop_source.py
Simulates BOPOverworldBiomeBuilder (primary, rare, secondary) and
BOPNetherRegionCommon/Rare to produce noise parameter entries for
BiomesOPlenty biomes.

Output: worldgen_data/biomesoplenty.json
Run from repo root: python pythonScripts/worldgen/parse_bop_source.py
"""

import json
from pathlib import Path

from bop.builder import BOPBuilder, BOPRareBuilder, BOPSecondaryBuilder

OUTPUT_DIR = Path('worldgen_data')
NAMESPACE  = 'biomesoplenty:'


def depth_section(depth: list) -> str:
    depth_min, depth_max = depth[0], depth[1]
    if depth_max <= 0.05: return 'surface'
    if depth_min >= 1.05: return 'deep'
    if depth_min >= 0.15: return 'caves'
    return 'underground'


def _pt(x: float) -> list:
    return [round(x, 6), round(x, 6)]


def _entry(biome: str, temp, hum, cont, erosion, depth, weird, offset) -> dict:
    return {'biome': biome, 'parameters': {
        'temperature':     temp,     'humidity':  hum,
        'continentalness': cont,     'erosion':   erosion,
        'depth':           depth,    'weirdness': weird,
        'offset':          offset,
    }}


# Decoded from BOPNetherRegionCommon and BOPNetherRegionRare class bytecode.
# addBiome(consumer, temp, hum, cont, erosion, depth, weirdness, offset, biome)
# All use Climate.Parameter.point(x) → stored as [x, x].
# Biomes that require the mod to be loaded use BOPBiomes.*; vanilla fallbacks dropped.
_NETHER_ENTRIES_RAW = [
    # ── BOPNetherRegionCommon ────────────────────────────────────────────────
    ('minecraft:nether_wastes',       0.0,   0.0,  0.0, 0.0, 0.0, 0.0, 0.0  ),
    ('biomesoplenty:crystalline_chasm',-0.5,  0.0,  0.0, 0.0, 0.0, 0.0, 0.0  ),
    ('biomesoplenty:undergrowth',      0.4,   0.0,  0.0, 0.0, 0.0, 0.0, 0.0  ),
    ('biomesoplenty:visceral_heap',    0.0,   0.5,  0.0, 0.0, 0.0, 0.0, 0.375),
    ('biomesoplenty:erupting_inferno', -0.5,  0.0,  0.0, 0.0, 0.0, 0.0, 0.175),
    # ── BOPNetherRegionRare ──────────────────────────────────────────────────
    ('biomesoplenty:withered_abyss',   0.0,   0.0,  0.0, 0.0, 0.0, 0.0, 0.0  ),
    ('biomesoplenty:crystalline_chasm',-0.5,  0.0,  0.0, 0.0, 0.0, 0.0, 0.0  ),
    ('biomesoplenty:undergrowth',      0.4,   0.0,  0.0, 0.0, 0.0, 0.0, 0.0  ),
    ('biomesoplenty:visceral_heap',    0.0,   0.5,  0.0, 0.0, 0.0, 0.0, 0.375),
    ('biomesoplenty:erupting_inferno', -0.5,  0.0,  0.0, 0.0, 0.0, 0.0, 0.175),
]


def collect_nether_entries() -> list:
    seen   = set()
    result = []
    for row in _NETHER_ENTRIES_RAW:
        biome, temp, hum, cont, erosion, depth, weird, offset = row
        entry = _entry(biome, _pt(temp), _pt(hum), _pt(cont),
                       _pt(erosion), _pt(depth), _pt(weird), offset)
        key = (biome, temp, hum, cont, erosion, depth, weird, offset)
        if key not in seen:
            seen.add(key)
            result.append(entry)
    return result


def collect_all_overworld_entries() -> list:
    entries = []
    for builder_class in (BOPBuilder, BOPRareBuilder, BOPSecondaryBuilder):
        entries.extend(builder_class().run())
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


def write_output(overworld_sections: dict, nether_sections: dict, namespace_label: str) -> None:
    OUTPUT_DIR.mkdir(exist_ok=True)
    path = OUTPUT_DIR / f'{namespace_label}.json'

    if path.exists():
        existing = json.loads(path.read_text(encoding='utf-8'))
        dims = existing.get('dimensions', {})
    else:
        dims = {}

    dims['minecraft:overworld'] = overworld_sections
    dims['minecraft:nether']    = nether_sections
    output = {'namespace': namespace_label, 'dimensions': dims}

    ow_count = len({e['biome'] for s in overworld_sections.values() for e in s})
    ne_count = len({e['biome'] for s in nether_sections.values()    for e in s})
    print(f"BiomesOPlenty overworld: {ow_count} unique biomes")
    print(f"BiomesOPlenty nether:    {ne_count} unique biomes — "
          f"{', '.join(e['biome'].split(':')[1] for s in nether_sections.values() for e in s if e['biome'].startswith('biomesoplenty:'))}")
    path.write_text(json.dumps(output, indent=2), encoding='utf-8')
    print(f"Written -> {path}")


def main():
    ow_all      = collect_all_overworld_entries()
    ow_entries  = filter_to_namespace(ow_all, NAMESPACE)
    ow_sections = group_by_section(ow_entries)

    ne_entries  = collect_nether_entries()
    ne_sections = group_by_section(ne_entries)

    write_output(ow_sections, ne_sections, 'biomesoplenty')


main()
