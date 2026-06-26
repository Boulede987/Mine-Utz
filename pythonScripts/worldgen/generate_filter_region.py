"""
generate_filter_region.py
=========================
Reads dimension_biome_planner.xlsx ✓ columns and generates TerraBlender
region Java files + FilterMod.java into the companion mod.

Overworld → FilterRegion.java        (RegionType.OVERWORLD, always)
Nether    → FilterNetherRegion.java  (RegionType.NETHER, only if ✓ biomes exist)
End       → FilterEndBiomes.java     (EndBiomeRegistry calls, only if ✓ biomes exist)
FilterMod.java is regenerated to register whichever regions were written.

Run after steps 21 (merge) and 22 (complete planner).

Usage:
    python pythonScripts/worldgen/generate_filter_region.py
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import openpyxl

from worldgen.constants import (
    CHECK_MARK, BIOME_PLANNER_FILE, BIOME_PLANNER_SHEET,
    ALL_SECTIONS, END_SECTIONS, OVERWORLD_PLANNER_HEADER,
)
from worldgen.terrablender_codegen import build_region, build_end_biomes, build_filter_mod

sys.stdout.reconfigure(encoding='utf-8')

NETHER_HEADER   = 'Nether'
END_HEADER      = 'End'
MERGED_DATA_DIR = Path('worldgen_data') / 'merged'

MOD_SRC = Path(
    'companion-mods/terrablender-custom-region'
    '/src/main/java/com/mineutze/filtermod'
)
FILTER_REGION        = MOD_SRC / 'FilterRegion.java'
FILTER_NETHER_REGION = MOD_SRC / 'FilterNetherRegion.java'
FILTER_END_BIOMES    = MOD_SRC / 'FilterEndBiomes.java'
FILTER_MOD           = MOD_SRC / 'FilterMod.java'


def load_column_biomes(worksheet, headers: list, column_name: str) -> list[str]:
    """Return biome IDs marked ✓ in the named column. Returns [] if column absent."""
    try:
        col_idx = headers.index(column_name)
    except ValueError:
        return []
    return [
        str(row[0]) for row in worksheet.iter_rows(min_row=2, values_only=True)
        if row[0] and ':' in str(row[0]) and row[col_idx] == CHECK_MARK
    ]


def load_all_params() -> dict[str, list[dict]]:
    """Returns {biome_id: [parameters, ...]} across all merged namespace files."""
    if not MERGED_DATA_DIR.exists():
        print('ERROR: worldgen_data/merged/ not found. Run step 21 first.')
        raise SystemExit(1)

    lookup: dict[str, list[dict]] = {}
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        for dim_sections in data.get('dimensions', {}).values():
            for section in ALL_SECTIONS:
                for entry in dim_sections.get(section, []):
                    lookup.setdefault(entry['biome'], []).append(entry['parameters'])
    return lookup


def load_merged_end_data() -> dict:
    """{biome_id: {placement_type, weight}} from minecraft:the_end across all merged files."""
    if not MERGED_DATA_DIR.exists():
        return {}

    lookup: dict = {}
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        end_sections = data.get('dimensions', {}).get('minecraft:the_end', {})
        for placement_type in END_SECTIONS:
            for entry in end_sections.get(placement_type, []):
                biome = entry['biome']
                if biome not in lookup:
                    lookup[biome] = {'placement_type': placement_type, 'weight': entry['weight']}
    return lookup


def main() -> None:
    if not BIOME_PLANNER_FILE.exists():
        print(f'ERROR: {BIOME_PLANNER_FILE} not found.')
        raise SystemExit(1)

    worksheet = openpyxl.load_workbook(BIOME_PLANNER_FILE)[BIOME_PLANNER_SHEET]
    headers   = [cell.value for cell in worksheet[1]]

    overworld_biomes = load_column_biomes(worksheet, headers, OVERWORLD_PLANNER_HEADER)
    nether_biomes    = load_column_biomes(worksheet, headers, NETHER_HEADER)
    end_biomes       = load_column_biomes(worksheet, headers, END_HEADER)

    if not overworld_biomes:
        print('No biomes marked ✓ in Overworld column — nothing to write.')
        raise SystemExit(0)

    all_params = load_all_params()
    end_data   = load_merged_end_data()

    build_region(overworld_biomes, all_params, 'FilterRegion', 'OVERWORLD', FILTER_REGION)

    has_nether = bool(nether_biomes)
    if has_nether:
        build_region(nether_biomes, all_params, 'FilterNetherRegion', 'NETHER', FILTER_NETHER_REGION)
    elif FILTER_NETHER_REGION.exists():
        FILTER_NETHER_REGION.unlink()
        print(f'  Removed {FILTER_NETHER_REGION.name} (no Nether biomes selected).')

    has_end = bool(end_biomes)
    if has_end:
        build_end_biomes(end_biomes, end_data, FILTER_END_BIOMES)
    elif FILTER_END_BIOMES.exists():
        FILTER_END_BIOMES.unlink()
        print(f'  Removed {FILTER_END_BIOMES.name} (no End biomes selected).')

    build_filter_mod(FILTER_MOD, has_nether, has_end)

    print('\nRebuild the companion mod (deployMod in IntelliJ) to apply.')


main()
