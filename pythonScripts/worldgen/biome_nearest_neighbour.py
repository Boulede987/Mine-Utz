"""
worldgen/biome_nearest_neighbour.py
=====================================
Shared data loading and nearest-neighbour computation for biome layout visualizers.

The fitness metric mirrors TerraBlender's Climate.ParameterPoint.fitness:
sum of squared distances from each query value to its registered [lo, hi] range,
zero when the query falls inside the range.
"""

import json
from pathlib import Path

import numpy as np
import openpyxl

from worldgen.constants import (
    ALL_SECTIONS, BIOME_PLANNER_FILE, BIOME_PLANNER_SHEET,
    CHECK_MARK, OVERWORLD_PLANNER_HEADER, PARAMETER_AXES,
)

MERGED_DATA_DIR = Path('worldgen_data') / 'merged'

# Entries processed per numpy batch. Lower = less peak memory, more iterations.
NEAREST_NEIGHBOUR_CHUNK_SIZE = 100

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
    """
    Loads all overworld climate entries for the selected biomes.
    All sections (surface, underground, caves, deep) are included — cave entries
    lose naturally when visualising at depth=0 due to their depth-axis penalty.
    """
    included_biomes = set(planner_biomes)
    entries: list[dict] = []
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data              = json.loads(json_file.read_text(encoding='utf-8'))
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
        lo_values      = [e['parameters'][axis][0] for e in entries]
        hi_values      = [e['parameters'][axis][1] for e in entries]
        result[f'{axis}_lo'] = min(lo_values)
        result[f'{axis}_hi'] = max(hi_values)
    return result


def build_parameter_bounds(entries: list[dict]) -> tuple[np.ndarray, np.ndarray]:
    """Returns (lo_array, hi_array) each of shape (N_entries, 6)."""
    lo_array = np.zeros((len(entries), 6))
    hi_array = np.zeros((len(entries), 6))
    for entry_index, entry in enumerate(entries):
        params = entry['parameters']
        for axis_index, axis in enumerate(PARAMETER_AXES):
            lo_array[entry_index, axis_index] = params[axis][0]
            hi_array[entry_index, axis_index] = params[axis][1]
    return lo_array, hi_array


def compute_nearest_entry_grid(
    lo_array:     np.ndarray,
    hi_array:     np.ndarray,
    query_grid:   np.ndarray,
    weight_array: np.ndarray | None = None,
) -> np.ndarray:
    """
    For each pixel in query_grid (shape H×W×6), finds the entry with minimum fitness.
    Returns (H, W) int32 array of entry indices into the original entries list.

    weight_array: optional (N_entries,) array. Zero-weight entries are excluded entirely.
    Non-zero weights divide each entry's fitness, making higher-weight biomes win more territory.
    """
    active_lo, active_hi, active_weights, original_indices = _filter_active_entries(
        lo_array, hi_array, weight_array
    )

    height, width = query_grid.shape[0], query_grid.shape[1]
    row_indices, col_indices = np.meshgrid(np.arange(height), np.arange(width), indexing='ij')

    min_fitness   = np.full((height, width), np.inf)
    nearest_local = np.zeros((height, width), dtype=np.int32)

    for chunk_start in range(0, active_lo.shape[0], NEAREST_NEIGHBOUR_CHUNK_SIZE):
        chunk_end     = min(chunk_start + NEAREST_NEIGHBOUR_CHUNK_SIZE, active_lo.shape[0])
        chunk_weights = active_weights[chunk_start:chunk_end] if active_weights is not None else None
        chunk_fitness = _chunk_fitness(
            active_lo[chunk_start:chunk_end],
            active_hi[chunk_start:chunk_end],
            query_grid,
            chunk_weights,
        )
        _merge_chunk_into_nearest(
            chunk_fitness, chunk_start, min_fitness, nearest_local, row_indices, col_indices
        )

    if original_indices is not None:
        return original_indices[nearest_local]
    return nearest_local


def _filter_active_entries(
    lo_array:     np.ndarray,
    hi_array:     np.ndarray,
    weight_array: np.ndarray | None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray | None, np.ndarray | None]:
    """Removes weight=0 entries before nearest-neighbour search.

    Returns (lo, hi, weights, original_indices). original_indices is None when
    no filtering was needed — returned nearest indices then map directly to entries.
    """
    if weight_array is None:
        return lo_array, hi_array, None, None
    active_indices = np.where(weight_array > 0)[0]
    if active_indices.size == lo_array.shape[0]:
        return lo_array, hi_array, weight_array, None
    return (
        lo_array[active_indices],
        hi_array[active_indices],
        weight_array[active_indices],
        active_indices,
    )


def _chunk_fitness(
    chunk_lo:      np.ndarray,
    chunk_hi:      np.ndarray,
    query_grid:    np.ndarray,
    chunk_weights: np.ndarray | None = None,
) -> np.ndarray:
    """Returns (chunk_size, H, W) fitness for one batch. Divided by weights when provided."""
    chunk_size    = chunk_lo.shape[0]
    height, width = query_grid.shape[0], query_grid.shape[1]
    fitness       = np.zeros((chunk_size, height, width))

    for axis_index in range(6):
        axis_lo    = chunk_lo[:, axis_index]
        axis_hi    = chunk_hi[:, axis_index]
        axis_query = query_grid[:, :, axis_index]

        dist_below = np.maximum(axis_lo[:, np.newaxis, np.newaxis] - axis_query[np.newaxis], 0.0)
        dist_above = np.maximum(axis_query[np.newaxis] - axis_hi[:, np.newaxis, np.newaxis], 0.0)
        fitness   += np.maximum(dist_below, dist_above) ** 2

    if chunk_weights is not None:
        fitness /= chunk_weights[:, np.newaxis, np.newaxis]

    return fitness


def _merge_chunk_into_nearest(
    chunk_fitness:  np.ndarray,
    chunk_start:    int,
    min_fitness:    np.ndarray,
    nearest_entry:  np.ndarray,
    row_indices:    np.ndarray,
    col_indices:    np.ndarray,
) -> None:
    """Updates min_fitness and nearest_entry in-place from one chunk's results."""
    local_best       = np.argmin(chunk_fitness, axis=0)
    local_best_score = chunk_fitness[local_best, row_indices, col_indices]
    improved              = local_best_score < min_fitness
    min_fitness[improved] = local_best_score[improved]
    nearest_entry[improved] = chunk_start + local_best[improved]
