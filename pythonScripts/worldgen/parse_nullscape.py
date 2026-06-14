"""
parse_nullscape.py
==================
Extracts Nullscape End biomes from the mod jar's datapack JSON and writes
worldgen_data/nullscape.json.

Nullscape replaces the End's biome source with minecraft:multi_noise, giving
its biomes full MultiNoise parameters. No Java code generation is needed —
placement is handled entirely by the datapack.

Biomes land in the 'surface' section of minecraft:the_end so merge and the
biome planner treat them like any overworld-style entry (with parameters).

Usage:
    python pythonScripts/worldgen/parse_nullscape.py
"""

import json
import sys
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')

MODS_DIR       = Path('minecraft/mods')
OUTPUT_DIR     = Path('worldgen_data')
OUTPUT_FILE    = OUTPUT_DIR / 'nullscape.json'
NAMESPACE      = 'nullscape'
DIMENSION_PATH = 'data/minecraft/dimension/the_end.json'
PARAMETER_AXES = ['temperature', 'humidity', 'continentalness', 'erosion', 'depth', 'weirdness']


def _norm(v) -> list[float]:
    """Scalar or [lo, hi] → [float, float]."""
    if isinstance(v, list):
        return [float(v[0]), float(v[1])]
    return [float(v), float(v)]


def find_jar() -> Path | None:
    for jar in sorted(MODS_DIR.glob('*.jar')):
        if 'nullscape' in jar.name.lower():
            return jar
    return None


def extract_end_biomes(jar_path: Path) -> list[dict]:
    with zipfile.ZipFile(jar_path) as z:
        if DIMENSION_PATH not in z.namelist():
            raise FileNotFoundError(f'{DIMENSION_PATH} not found in {jar_path.name}')
        raw = json.loads(z.read(DIMENSION_PATH))

    seen: set[str] = set()
    entries: list[dict] = []
    for item in raw['generator']['biome_source']['biomes']:
        biome_id = item['biome']
        if not biome_id.startswith(f'{NAMESPACE}:') or biome_id in seen:
            continue
        seen.add(biome_id)
        p = item['parameters']
        parameters = {axis: _norm(p[axis]) for axis in PARAMETER_AXES}
        parameters['offset'] = float(p.get('offset', 0.0))
        entries.append({'biome': biome_id, 'parameters': parameters})

    return sorted(entries, key=lambda e: e['biome'])


def main() -> None:
    jar = find_jar()
    if not jar:
        print(f'ERROR: No Nullscape jar found in {MODS_DIR}/')
        raise SystemExit(1)
    print(f'Reading {jar.name}')

    entries = extract_end_biomes(jar)
    if not entries:
        print('No nullscape: biomes found.')
        raise SystemExit(1)

    output = {
        'namespace': NAMESPACE,
        'dimensions': {
            'minecraft:the_end': {
                'surface': entries,
            }
        },
    }
    OUTPUT_DIR.mkdir(exist_ok=True)
    OUTPUT_FILE.write_text(json.dumps(output, indent=2), encoding='utf-8')
    print(f'  {len(entries)} biome(s) → {OUTPUT_FILE}')
    for e in entries:
        print(f'    {e["biome"]}')


main()
