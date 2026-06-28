"""
worldgen/tile_assignments.py
=============================
Persistence for tile map biome assignments.
"""

import json
from pathlib import Path

from worldgen.biome_grid import (
    LAND_HUMIDITY_BANDS, GridCell, build_land_cells, build_ocean_cells,
    tile_map_to_entries,
)

TILE_ASSIGNMENTS_FILE = Path('worldgen_data') / 'tile_assignments.json'


def load_tile_assignments() -> dict[str, dict]:
    """Returns {'land': {key: biome_id}, 'ocean': {key: biome_id}} or empty dicts."""
    if not TILE_ASSIGNMENTS_FILE.exists():
        return {'land': {}, 'ocean': {}}
    return json.loads(TILE_ASSIGNMENTS_FILE.read_text(encoding='utf-8'))


def save_tile_assignments(assignments: dict[str, dict]) -> None:
    TILE_ASSIGNMENTS_FILE.parent.mkdir(parents=True, exist_ok=True)
    TILE_ASSIGNMENTS_FILE.write_text(
        json.dumps(assignments, indent=2, ensure_ascii=False),
        encoding='utf-8',
    )


def tile_editor_to_entries(
    land_assignments:  dict[str, str],
    ocean_assignments: dict[str, str],
) -> list[dict]:
    """Converts tile editor assignments to TerraBlender entries."""
    land_cells  = build_land_cells()
    ocean_cells = build_ocean_cells()
    cell_to_biome: dict[GridCell, str] = {}

    for key, biome_id in land_assignments.items():
        temp_idx, hum_idx = map(int, key.split(','))
        cell_to_biome[land_cells[temp_idx * LAND_HUMIDITY_BANDS + hum_idx]] = biome_id

    for key, biome_id in ocean_assignments.items():
        cell_to_biome[ocean_cells[int(key)]] = biome_id

    return tile_map_to_entries(cell_to_biome)
