"""
generate_filter_region.py
=========================
Reads dimension_biome_planner.xlsx ✓ columns and generates TerraBlender region Java
files for Nether and End into the companion mod.

Nether → FilterNetherRegion.java  (RegionType.NETHER, only if ✓ biomes exist)
End    → FilterEndBiomes.java     (EndBiomeRegistry calls, only if ✓ biomes exist)

Overworld placement no longer goes through this script: it's the hand-maintained
CustomOverworldBiomeSource Java (companion-mods/mineutze-worldgen) plus, eventually,
a generated runtime config (see the implementation plan's "Runtime config delivery" /
generate_patch_config.py, not yet written). FilterMod.java is also gone, replaced by
hand-maintained WorldgenMod.java — this script no longer regenerates a mod bootstrap.

## Why this exists

TerraBlender lets mods register custom biomes by claiming climate parameter slots.
Without filtering, BiomedOPlenty and similar mods register ALL their biomes in ALL
dimensions — including biomes the modpack doesn't want. For Nether/End, this mod still
acts as a whitelist: only biomes marked ✓ in dimension_biome_planner.xlsx are
re-registered.

## Why wide climate spans cause square biome borders

BiomedOPlenty registers each biome at multiple specific, narrow climate positions.
If we collapse those into one wide span (e.g. temperature [0.2, 1.0]) to simplify,
the biome dominates a huge region of the 6D climate space. TerraBlender resolves
nearest-neighbor ties at exact chunk boundaries, so wide spans produce chunk-aligned
square borders. The fix: keep every distinct specific position from the raw scan data
(merge_biome_parameters.py dedup mode), then emit one addBiome call per position.
A biome with 116 original positions gets 116 addBiome calls — which is correct.

## Pipeline

    jar scan → worldgen_data/*.json
    step 21: merge_biome_parameters.py  → worldgen_data/merged/*.json  (dedup, all positions)
    step 22: dimension_biome_planner.xlsx  (mark ✓ which biomes to include per dimension)
    this script  → FilterNetherRegion.java / FilterEndBiomes.java
    IntelliJ deployMod → companion mod jar → load in game

Run after steps 21 (merge) and 22 (complete planner). Nether/End are not app-tunable
and use the raw scanned positions from worldgen_data/merged/.

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
    ALL_SECTIONS, END_SECTIONS, MERGED_BIOME_DATA_DIR,
)
from worldgen.terrablender_codegen import build_region, build_end_biomes

sys.stdout.reconfigure(encoding='utf-8')

NETHER_HEADER = 'Nether'
END_HEADER    = 'End'

MOD_SRC = Path(
    'companion-mods/mineutze-worldgen'
    '/src/main/java/com/mineutze/worldgen'
)
FILTER_NETHER_REGION = MOD_SRC / 'FilterNetherRegion.java'
FILTER_END_BIOMES    = MOD_SRC / 'FilterEndBiomes.java'


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
    if not MERGED_BIOME_DATA_DIR.exists():
        print('ERROR: worldgen_data/merged/ not found. Run step 21 first.')
        raise SystemExit(1)

    lookup: dict[str, list[dict]] = {}
    for json_file in sorted(MERGED_BIOME_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        for dim_sections in data.get('dimensions', {}).values():
            for section in ALL_SECTIONS:
                for entry in dim_sections.get(section, []):
                    lookup.setdefault(entry['biome'], []).append(entry['parameters'])
    return lookup


def load_merged_end_data() -> dict:
    """{biome_id: {placement_type, weight}} from minecraft:the_end across all merged files."""
    if not MERGED_BIOME_DATA_DIR.exists():
        return {}

    lookup: dict = {}
    for json_file in sorted(MERGED_BIOME_DATA_DIR.glob('*.json')):
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

    nether_biomes = load_column_biomes(worksheet, headers, NETHER_HEADER)
    end_biomes    = load_column_biomes(worksheet, headers, END_HEADER)

    if not nether_biomes and not end_biomes:
        print('No biomes marked ✓ in Nether/End columns — nothing to write.')
        raise SystemExit(0)

    all_params = load_all_params()
    end_data   = load_merged_end_data()

    if nether_biomes:
        build_region(nether_biomes, all_params, 'FilterNetherRegion', 'NETHER', FILTER_NETHER_REGION)
    elif FILTER_NETHER_REGION.exists():
        FILTER_NETHER_REGION.unlink()
        print(f'  Removed {FILTER_NETHER_REGION.name} (no Nether biomes selected).')

    if end_biomes:
        build_end_biomes(end_biomes, end_data, FILTER_END_BIOMES)
    elif FILTER_END_BIOMES.exists():
        FILTER_END_BIOMES.unlink()
        print(f'  Removed {FILTER_END_BIOMES.name} (no End biomes selected).')

    print('\nRebuild the companion mod (deployMod in IntelliJ) to apply.')


main()
