"""
worldgen/biome_colour.py
=========================
Deterministic colour assignment and image construction for biome visualizers.
"""

import colorsys
import hashlib

import numpy as np


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
