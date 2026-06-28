"""
worldgen/biome_colour.py
=========================
Deterministic colour assignment and image construction for biome visualizers.
"""

import colorsys
import hashlib

import numpy as np
from scipy.ndimage import label as ndimage_label

_EIGHT_CONNECTIVITY = np.ones((3, 3), dtype=int)


def biome_color_rgb(biome_id: str) -> tuple[float, float, float]:
    """Deterministic HSV colour derived from biome ID hash, converted to RGB."""
    hash_bytes = hashlib.md5(biome_id.encode()).digest()
    hue        = int.from_bytes(hash_bytes[:2], 'big') / 65536.0
    saturation = 0.55 + (hash_bytes[2] / 255.0) * 0.45
    value      = 0.65 + (hash_bytes[3] / 255.0) * 0.35
    return colorsys.hsv_to_rgb(hue, saturation, value)


def build_rgb_image(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
) -> tuple[np.ndarray, dict[str, tuple]]:
    """Returns (H, W, 3) float RGB image and {biome_id: rgb_tuple} colour map."""
    biome_colors = {entry['biome']: biome_color_rgb(entry['biome']) for entry in entries}
    entry_biomes = [entry['biome'] for entry in entries]
    image        = np.zeros((*nearest_entry_grid.shape, 3))

    for unique_index in np.unique(nearest_entry_grid):
        mask          = nearest_entry_grid == unique_index
        image[mask]   = biome_colors[entry_biomes[unique_index]]

    return image, biome_colors


_DIM_FACTOR = 0.08


def build_highlighted_image(
    nearest_entry_grid:   np.ndarray,
    entries:              list[dict],
    highlighted_biome_id: str,
    biome_colors:         dict[str, tuple],
) -> np.ndarray:
    """Returns (H, W, 3) image with highlighted biome at full color, all others dimmed."""
    entry_biomes = [entry['biome'] for entry in entries]
    image        = np.zeros((*nearest_entry_grid.shape, 3))
    for unique_index in np.unique(nearest_entry_grid):
        biome = entry_biomes[unique_index]
        mask  = nearest_entry_grid == unique_index
        color = biome_colors[biome]
        if biome == highlighted_biome_id:
            image[mask] = color
        else:
            image[mask] = tuple(channel * _DIM_FACTOR for channel in color)
    return image


def compute_biome_coverage(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
) -> dict[str, float]:
    """Returns fraction of pixels won by each biome; values sum to 1.0."""
    total_pixels = nearest_entry_grid.size
    entry_biomes = [entry['biome'] for entry in entries]
    coverage: dict[str, float] = {}
    for unique_index in np.unique(nearest_entry_grid):
        biome              = entry_biomes[unique_index]
        pixel_count        = int(np.sum(nearest_entry_grid == unique_index))
        coverage[biome]    = coverage.get(biome, 0) + pixel_count / total_pixels
    return coverage


def compute_patch_stats(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
) -> dict[str, dict]:
    """Returns per-biome patch fragmentation stats.

    Each value: {'patch_count', 'median_patch_pct', 'largest_patch_pct'}.
    Biomes with no pixels on the grid are omitted.
    """
    total_pixels           = nearest_entry_grid.size
    biome_to_entry_indices = _group_entry_indices_by_biome(entries)
    return {
        biome_id: biome_stats
        for biome_id, entry_indices in biome_to_entry_indices.items()
        if (biome_stats := _patch_stats_for_biome(nearest_entry_grid, entry_indices, total_pixels)) is not None
    }


def _group_entry_indices_by_biome(entries: list[dict]) -> dict[str, list[int]]:
    biome_to_indices: dict[str, list[int]] = {}
    for index, entry in enumerate(entries):
        biome_to_indices.setdefault(entry['biome'], []).append(index)
    return biome_to_indices


def _patch_stats_for_biome(
    nearest_entry_grid: np.ndarray,
    entry_indices:      list[int],
    total_pixels:       int,
) -> dict | None:
    mask = np.isin(nearest_entry_grid, entry_indices)
    if not mask.any():
        return None
    labeled_array, patch_count = ndimage_label(mask, structure=_EIGHT_CONNECTIVITY)
    patch_pixel_counts = np.bincount(labeled_array.ravel())[1:]  # skip background label 0
    return {
        'patch_count':      patch_count,
        'median_patch_pct': float(np.median(patch_pixel_counts)) / total_pixels * 100,
        'largest_patch_pct': float(patch_pixel_counts.max()) / total_pixels * 100,
    }
