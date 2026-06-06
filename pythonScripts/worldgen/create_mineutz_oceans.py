"""
create_mineutz_oceans.py
========================
Creates mineutz-namespaced ocean biome copies for every dimension in the
planner, plus plain overworld versions.

Generated names:
  Overworld           → mineutz:ocean, mineutz:deep_ocean, …
  Zombie City         → mineutz:zombie_city_ocean, mineutz:zombie_city_deep_ocean, …
  Pokemon Dimension   → mineutz:pokemon_dimension_ocean, …
  (any new column added to the planner is picked up automatically)

Outputs:
  1. Paxi datapack biome JSONs — one file per biome under
       data/mineutz/worldgen/biome/<name>.json
     Content copied verbatim from the vanilla jar; all minecraft: feature /
     carver references still resolve correctly under any namespace.

  2. worldgen_data/mineutz_oceans.json — pre-merge source consumed by
     merge_biome_parameters.py (step 21).

Run this once (or after a MC version bump or adding a new dimension column),
then re-run steps 21 → 22 → 23 → 24.

Usage:
    python pythonScripts/worldgen/create_mineutz_oceans.py
"""

import json
import re
import sys
import zipfile
from pathlib import Path

import openpyxl

sys.stdout.reconfigure(encoding='utf-8')

VANILLA_JAR      = (
    Path.home()
    / 'AppData' / 'Roaming' / 'PrismLauncher'
    / 'libraries' / 'com' / 'mojang' / 'minecraft'
    / '1.21.1' / 'minecraft-1.21.1-client.jar'
)
MERGED_MINECRAFT = Path('worldgen_data') / 'merged' / 'minecraft.json'
WORLDGEN_SOURCE  = Path('worldgen_data') / 'mineutz_oceans.json'
BIOME_DEST_DIR   = Path('minecraft/config/paxi/datapacks/mineutz/data/mineutz/worldgen/biome')
PLANNER_FILE     = Path('dimension_biome_planner.xlsx')
SHEET_NAME       = 'Biome Planner'

# Overworld column is handled by FilterRegion (companion mod), not a dim JSON,
# but we still generate plain mineutz:ocean* for it.
OVERWORLD_HEADER = 'Overworld'

OCEAN_NAMES = [
    'ocean',
    'deep_ocean',
    'cold_ocean',
    'deep_cold_ocean',
    'frozen_ocean',
    'deep_frozen_ocean',
    'lukewarm_ocean',
    'deep_lukewarm_ocean',
    'warm_ocean',
]


def slugify(header: str) -> str:
    return re.sub(r'[^a-z0-9]+', '_', header.lower()).strip('_')


def load_dimension_slugs() -> list[tuple[str, str | None]]:
    """
    Returns [(prefix, header)] for every dimension column in the planner.
    Overworld → prefix '' (plain names, no underscore prefix).
    Custom dims → prefix '<slug>_'.
    """
    if not PLANNER_FILE.exists():
        print(f'WARNING: {PLANNER_FILE} not found — using Overworld only.')
        return [('', OVERWORLD_HEADER)]

    ws = openpyxl.load_workbook(PLANNER_FILE)[SHEET_NAME]
    headers = [cell.value for cell in ws[1]][4:]  # E onward

    result = []
    for h in headers:
        if not h:
            continue
        if h == OVERWORLD_HEADER:
            result.append(('', h))
        else:
            result.append((slugify(h) + '_', h))
    return result


def load_vanilla_ocean_jsons() -> dict[str, bytes]:
    """Returns {ocean_name: raw_bytes} for each ocean biome from the vanilla jar."""
    if not VANILLA_JAR.exists():
        print(f'ERROR: vanilla jar not found at {VANILLA_JAR}')
        raise SystemExit(1)

    raw: dict[str, bytes] = {}
    with zipfile.ZipFile(VANILLA_JAR) as jar:
        for name in OCEAN_NAMES:
            jar_path = f'data/minecraft/worldgen/biome/{name}.json'
            if jar_path not in jar.namelist():
                print(f'  WARNING: {jar_path} not in jar — skipped')
                continue
            raw[name] = jar.read(jar_path)
    return raw


def load_param_lookup() -> dict[str, dict]:
    """Returns {biome_id: parameters} from worldgen_data/merged/minecraft.json."""
    if not MERGED_MINECRAFT.exists():
        print(f'ERROR: {MERGED_MINECRAFT} not found. Run step 21 first.')
        raise SystemExit(1)
    data = json.loads(MERGED_MINECRAFT.read_text(encoding='utf-8'))
    lookup: dict = {}
    for dim_sections in data.get('dimensions', {}).values():
        for section in ['surface', 'underground', 'caves', 'deep']:
            for entry in dim_sections.get(section, []):
                lookup[entry['biome']] = entry['parameters']
    return lookup


def main() -> None:
    dim_slugs  = load_dimension_slugs()
    ocean_raws = load_vanilla_ocean_jsons()
    params     = load_param_lookup()

    BIOME_DEST_DIR.mkdir(parents=True, exist_ok=True)

    all_surface_entries: list[dict] = []
    total_files = 0

    print(f'Dimensions: {[h for _, h in dim_slugs]}')
    print()

    for prefix, header in dim_slugs:
        print(f'--- {header} (prefix: "{prefix}") ---')
        for ocean_name in OCEAN_NAMES:
            if ocean_name not in ocean_raws:
                continue

            new_name  = f'{prefix}{ocean_name}'
            dest_id   = f'mineutz:{new_name}'
            dest_path = BIOME_DEST_DIR / f'{new_name}.json'

            # Write biome JSON (verbatim vanilla content)
            dest_path.write_text(
                ocean_raws[ocean_name].decode('utf-8'), encoding='utf-8'
            )
            total_files += 1

            # Parameters — same as the vanilla ocean biome regardless of prefix
            vanilla_id = f'minecraft:{ocean_name}'
            if vanilla_id not in params:
                print(f'  WARNING: no params for {vanilla_id} — skipped source entry')
                continue

            all_surface_entries.append({
                'biome':      dest_id,
                'parameters': params[vanilla_id],
            })
            print(f'  {dest_id}')

        print()

    source = {
        'namespace': 'mineutz',
        'note': 'mineutz: ocean biome copies per dimension. Generated by create_mineutz_oceans.py.',
        'dimensions': {
            'minecraft:overworld': {
                'surface': all_surface_entries,
            }
        },
    }
    WORLDGEN_SOURCE.write_text(json.dumps(source, indent=2), encoding='utf-8')

    print(f'Wrote {total_files} biome JSON files.')
    print(f'Wrote {WORLDGEN_SOURCE} ({len(all_surface_entries)} entries).')
    print("""
Next steps:
  21  Merge biome params
  22  Complete planner  (new rows added — mark ✓ per dimension)
  23  Generate FilterRegion Java
  24  Generate dimension JSONs
""")


main()
