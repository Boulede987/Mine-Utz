"""
worldgen/app_data.py
=====================
App-specific constants and helper functions for the Streamlit biome visualizer.
Keeps data-preparation logic separate from UI code in app.py.
"""

import numpy as np

PREVIEW_RESOLUTION            = 128   # pixels per axis for interactive preview
EXPORT_RESOLUTION             = 512   # pixels per axis for high-resolution export
PREVIEW_MAX_ENTRIES_PER_BIOME = 20    # subsample to this many per biome for speed
MAP_SEED                      = 42

WEIGHT_MIN     = 0.0
WEIGHT_MAX     = 3.0
WEIGHT_DEFAULT = 1.0
WEIGHT_STEP    = 0.05

SLICE_RESOLUTION = 100   # pixels per axis for the parameter-slice tab


AXIS_VALUE_RANGES = {
    'temperature':     (-1.5,  1.5),
    'humidity':        (-1.0,  1.0),
    'continentalness': (-1.1,  1.0),
    'erosion':         (-1.0,  1.0),
    'depth':           (-1.0,  1.5),
    'weirdness':       (-1.0,  1.0),
}

SLICE_CONFIGS = [
    {
        'name':   'temp_x_humidity__inland',
        'title':  'Temperature × Humidity (mid-inland, surface)',
        'x_axis': 'temperature',
        'y_axis': 'humidity',
        'fixed':  {'continentalness': 0.5, 'erosion': 0.0, 'depth': 0.0, 'weirdness': 0.0},
    },
    {
        'name':   'temp_x_humidity__coast',
        'title':  'Temperature × Humidity (coast, surface)',
        'x_axis': 'temperature',
        'y_axis': 'humidity',
        'fixed':  {'continentalness': -0.1, 'erosion': 0.0, 'depth': 0.0, 'weirdness': 0.0},
    },
    {
        'name':   'continental_x_erosion',
        'title':  'Continentalness × Erosion (neutral temp+humidity, surface)',
        'x_axis': 'continentalness',
        'y_axis': 'erosion',
        'fixed':  {'temperature': 0.0, 'humidity': 0.0, 'depth': 0.0, 'weirdness': 0.0},
    },
    {
        'name':   'temp_x_weirdness__inland',
        'title':  'Temperature × Weirdness (mid-inland, neutral humidity, surface)',
        'x_axis': 'temperature',
        'y_axis': 'weirdness',
        'fixed':  {'continentalness': 0.5, 'erosion': 0.0, 'depth': 0.0, 'humidity': 0.0},
    },
]



def subsample_entries_per_biome(entries: list[dict], max_per_biome: int) -> list[dict]:
    """Evenly subsamples entries so each biome has at most max_per_biome entries."""
    by_biome: dict[str, list[dict]] = {}
    for entry in entries:
        by_biome.setdefault(entry['biome'], []).append(entry)

    subsampled: list[dict] = []
    for biome_entries in by_biome.values():
        if len(biome_entries) <= max_per_biome:
            subsampled.extend(biome_entries)
        else:
            step     = len(biome_entries) / max_per_biome
            indices  = [int(i * step) for i in range(max_per_biome)]
            subsampled.extend(biome_entries[index] for index in indices)
    return subsampled


def group_biome_ids_by_namespace(biome_ids: list[str]) -> dict[str, list[str]]:
    """Returns {namespace: sorted list of paths} from a flat list of 'namespace:path' IDs."""
    groups: dict[str, set[str]] = {}
    for biome_id in biome_ids:
        namespace, path = biome_id.split(':', 1)
        groups.setdefault(namespace, set()).add(path)
    return {namespace: sorted(paths) for namespace, paths in sorted(groups.items())}


def group_biomes_by_namespace(entries: list[dict]) -> dict[str, list[str]]:
    """Returns {namespace: sorted list of biome paths} from entries."""
    return group_biome_ids_by_namespace([entry['biome'] for entry in entries])


def build_entry_weight_array(entries: list[dict], biome_weights: dict[str, float]) -> np.ndarray:
    """Converts {biome_id: weight} to a per-entry weight array matching entries order."""
    return np.array([biome_weights.get(entry['biome'], WEIGHT_DEFAULT) for entry in entries])


def effective_biome_weights(
    namespace_weights: dict[str, float],
    biome_weights:     dict[str, float],
    all_biome_ids:     list[str],
) -> dict[str, float]:
    """Multiplies per-biome weight by its namespace multiplier to get final weight."""
    result: dict[str, float] = {}
    for biome_id in all_biome_ids:
        namespace      = biome_id.split(':')[0]
        namespace_scale = namespace_weights.get(namespace, WEIGHT_DEFAULT)
        biome_scale     = biome_weights.get(biome_id, WEIGHT_DEFAULT)
        result[biome_id] = namespace_scale * biome_scale
    return result
