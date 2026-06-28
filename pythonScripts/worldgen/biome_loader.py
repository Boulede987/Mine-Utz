"""
worldgen/biome_loader.py
=========================
I/O boundary for biome data. All file access (xlsx planner, merged JSON data) is
isolated here so computation modules stay independent of storage format.
"""

import json

import numpy as np
import openpyxl

from worldgen.constants import (
    ALL_SECTIONS, BIOME_PLANNER_FILE, BIOME_PLANNER_SHEET,
    CHECK_MARK, MERGED_BIOME_DATA_DIR, OVERWORLD_PLANNER_HEADER, PARAMETER_AXES,
)

# Depth value below which an entry is considered a surface entry.
SURFACE_DEPTH_THRESHOLD = 0.1


def load_planner_overworld_biomes() -> list[str]:
    workbook  = openpyxl.load_workbook(BIOME_PLANNER_FILE)
    worksheet = workbook[BIOME_PLANNER_SHEET]
    headers   = [cell.value for cell in worksheet[1]]
    try:
        overworld_column_index = headers.index(OVERWORLD_PLANNER_HEADER)
    except ValueError:
        raise RuntimeError(f'Column "{OVERWORLD_PLANNER_HEADER}" not found in planner.')
    return [
        str(row[0])
        for row in worksheet.iter_rows(min_row=2, values_only=True)
        if row[0] and ':' in str(row[0]) and row[overworld_column_index] == CHECK_MARK
    ]


def load_overworld_entries(planner_biomes: list[str]) -> list[dict]:
    """Loads all overworld climate entries for the selected biomes.

    All sections (surface, underground, caves, deep) are included — cave entries
    lose naturally when visualising at depth=0 due to their depth-axis penalty.
    """
    included_biomes = set(planner_biomes)
    entries: list[dict] = []
    for json_file in sorted(MERGED_BIOME_DATA_DIR.glob('*.json')):
        data               = json.loads(json_file.read_text(encoding='utf-8'))
        overworld_sections = data.get('dimensions', {}).get('minecraft:overworld', {})
        for section in ALL_SECTIONS:
            for entry in overworld_sections.get(section, []):
                if entry['biome'] in included_biomes:
                    entries.append({'biome': entry['biome'], 'parameters': entry['parameters']})
    return entries


def load_biome_parameter_defaults(planner_biomes: list[str]) -> dict[str, dict]:
    """Returns per-biome parameter range defaults from actual registered entries.

    Prefers surface entries (depth < SURFACE_DEPTH_THRESHOLD); falls back to all
    entries for biomes that only register underground. Biomes with no data are omitted.
    Each value is a flat dict: {'temperature_lo', 'temperature_hi', 'humidity_lo', ...}
    """
    all_entries     = load_overworld_entries(planner_biomes)
    surface_entries = [e for e in all_entries if e['parameters']['depth'][0] < SURFACE_DEPTH_THRESHOLD]

    defaults: dict[str, dict] = {}
    for biome_id in planner_biomes:
        biome_surface = [e for e in surface_entries if e['biome'] == biome_id]
        biome_all     = [e for e in all_entries     if e['biome'] == biome_id]
        source        = biome_surface if biome_surface else biome_all
        if source:
            defaults[biome_id] = _extract_parameter_ranges(source)
    return defaults


def _extract_parameter_ranges(entries: list[dict]) -> dict:
    result: dict[str, float] = {}
    for axis in PARAMETER_AXES:
        lo_values         = [e['parameters'][axis][0] for e in entries]
        hi_values         = [e['parameters'][axis][1] for e in entries]
        result[f'{axis}_lo'] = min(lo_values)
        result[f'{axis}_hi'] = max(hi_values)
    return result
