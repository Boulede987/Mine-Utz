"""
generate_dimension_jsons.py
===========================
Reads dimension_biome_planner.xlsx and worldgen_data/merged/*.json.
For each custom dimension column, collects biomes marked ✓ and writes
an explicit multi_noise biomes array into the matching Paxi datapack
dimension JSON.

Each biome contributes ALL of its parameter entries (not a merged bounding
box) so that erosion/weirdness discrimination is preserved and sub-chunk
biome variation can occur.

Skips the Overworld column — overworld filtering is handled by the
TerraBlender companion mod (companion-mods/terrablender-custom-region/).

Usage:
    python pythonScripts/worldgen/generate_dimension_jsons.py
"""

import json
import re
import sys
from pathlib import Path

import openpyxl

sys.stdout.reconfigure(encoding='utf-8')

PLANNER_FILE    = Path('dimension_biome_planner.xlsx')
SHEET_NAME      = 'Biome Planner'
MERGED_DATA_DIR = Path('worldgen_data') / 'merged'
DIMENSION_DIR   = Path('minecraft/config/paxi/datapacks/mineutz/data/mineutz/dimension')
NAMESPACE       = 'mineutz'

SKIP_COLUMNS = {'Overworld', 'Nether', 'End'}
CHECK_MARK   = '✓'
ALL_SECTIONS = ['surface', 'underground', 'caves', 'deep']


def slugify(header: str) -> str:
    return re.sub(r'[^a-z0-9]+', '_', header.lower()).strip('_')


def load_all_parameters() -> dict[str, list[dict]]:
    """Returns {biome_id: [parameters, ...]} from all merged namespace files."""
    if not MERGED_DATA_DIR.exists():
        print('ERROR: worldgen_data/merged/ not found. Run merge_biome_parameters.py first.')
        raise SystemExit(1)
    params: dict[str, list[dict]] = {}
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        for dimension_sections in data.get('dimensions', {}).values():
            for section in ALL_SECTIONS:
                for entry in dimension_sections.get(section, []):
                    params.setdefault(entry['biome'], []).append(entry['parameters'])
    return params


def load_assignments(worksheet) -> dict:
    """Returns {header: [biome_id, ...]} for each non-skipped dimension column."""
    headers = [cell.value for cell in worksheet[1]]
    dim_headers = [h for h in headers[4:] if h and h not in SKIP_COLUMNS]
    assignments: dict = {h: [] for h in dim_headers}

    for row in worksheet.iter_rows(min_row=2, values_only=True):
        biome_id = row[0]
        if not (biome_id and ':' in str(biome_id)):
            continue
        for col_offset, header in enumerate(headers[4:]):
            if header in assignments and row[4 + col_offset] == CHECK_MARK:
                assignments[header].append(biome_id)

    return assignments


def build_json(slug: str, biome_entries: list) -> dict:
    return {
        'type': f'{NAMESPACE}:{slug}',
        'generator': {
            'type': 'minecraft:noise',
            'biome_source': {
                'type': 'minecraft:multi_noise',
                'biomes': biome_entries,
            },
            'settings': 'minecraft:overworld',
        },
    }


def main() -> None:
    if not PLANNER_FILE.exists():
        print(f'ERROR: {PLANNER_FILE} not found.')
        raise SystemExit(1)

    all_params  = load_all_parameters()
    worksheet   = openpyxl.load_workbook(PLANNER_FILE)[SHEET_NAME]
    assignments = load_assignments(worksheet)

    DIMENSION_DIR.mkdir(parents=True, exist_ok=True)

    for header, biome_ids in assignments.items():
        slug     = slugify(header)
        out_path = DIMENSION_DIR / f'{slug}.json'

        if not biome_ids:
            print(f'  [{header}] No ✓ biomes — skipped (would be empty)')
            continue

        biome_entries: list[dict] = []
        missing:       list[str]  = []

        for biome_id in sorted(biome_ids):
            param_list = all_params.get(biome_id)
            if not param_list:
                missing.append(biome_id)
                continue
            for p in param_list:
                biome_entries.append({
                    'biome': biome_id,
                    'parameters': {
                        'temperature':     p['temperature'],
                        'humidity':        p['humidity'],
                        'continentalness': p['continentalness'],
                        'erosion':         p['erosion'],
                        'depth':           p['depth'],
                        'weirdness':       p['weirdness'],
                        'offset':          p.get('offset', 0.0),
                    },
                })

        if missing:
            print(f'  [{header}] WARNING — {len(missing)} biome(s) not in merged data (no parameters):')
            for m in missing:
                print(f'    - {m}')

        out_path.write_text(json.dumps(build_json(slug, biome_entries), indent=2), encoding='utf-8')
        unique_biomes = len(set(e['biome'] for e in biome_entries))
        print(f'  [{header}] → {out_path}  ({unique_biomes} biomes, {len(biome_entries)} entries)')

    print('\nDone.')


main()
