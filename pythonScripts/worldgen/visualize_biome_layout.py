"""
worldgen/visualize_biome_layout.py
====================================
Renders 2D slices of the 6D TerraBlender climate space, showing which biome
wins nearest-neighbor at each query point. Outputs PNG files so distribution
can be tuned without launching the game.

The fitness algorithm mirrors TerraBlender's Climate.ParameterPoint.fitness:
sum of squared distances from each query value to the registered [lo, hi] range
(zero distance if the value falls inside the range).

Usage:
    python pythonScripts/worldgen/visualize_biome_layout.py

Dependencies: numpy, matplotlib  (pip install numpy matplotlib if missing)
"""

import colorsys
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
sys.stdout.reconfigure(encoding='utf-8')

try:
    import numpy as np
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import matplotlib.patches as mpatches
except ImportError as missing:
    print(f'ERROR: missing dependency — {missing}')
    print('Run: pip install numpy matplotlib')
    raise SystemExit(1)

import openpyxl

from worldgen.constants import (
    CHECK_MARK, BIOME_PLANNER_FILE, BIOME_PLANNER_SHEET,
    ALL_SECTIONS, OVERWORLD_PLANNER_HEADER,
)

PARAMETER_AXES = ['temperature', 'humidity', 'continentalness', 'erosion', 'depth', 'weirdness']

AXIS_VALUE_RANGES = {
    'temperature':     (-1.5,  1.5),
    'humidity':        (-1.0,  1.0),
    'continentalness': (-1.1,  1.0),
    'erosion':         (-1.0,  1.0),
    'depth':           (-1.0,  1.5),
    'weirdness':       (-1.0,  1.0),
}

GRID_RESOLUTION   = 200   # pixels per axis per slice
ENTRY_CHUNK_SIZE  = 100   # entries processed per numpy batch (memory vs speed tradeoff)
LEGEND_MAX_BIOMES = 20    # top N biomes labeled in legend; rest shown unlabeled

MERGED_DATA_DIR = Path('worldgen_data') / 'merged'
OUTPUT_DIR      = Path('worldgen_data') / 'visualizations'

# Each slice fixes 4 of the 6 axes and varies the remaining 2.
# Depth is fixed at 0.0 (surface) for all surface slices.
SLICE_CONFIGS = [
    {
        'name':   'temp_x_humidity__inland',
        'title':  'Temperature × Humidity  (mid-inland, surface, weirdness=0)',
        'x_axis': 'temperature',
        'y_axis': 'humidity',
        'fixed':  {'continentalness': 0.5, 'erosion': 0.0, 'depth': 0.0, 'weirdness': 0.0},
    },
    {
        'name':   'temp_x_humidity__coast',
        'title':  'Temperature × Humidity  (coast, surface, weirdness=0)',
        'x_axis': 'temperature',
        'y_axis': 'humidity',
        'fixed':  {'continentalness': -0.1, 'erosion': 0.0, 'depth': 0.0, 'weirdness': 0.0},
    },
    {
        'name':   'continental_x_erosion__neutral',
        'title':  'Continentalness × Erosion  (neutral temp+humidity, surface, weirdness=0)',
        'x_axis': 'continentalness',
        'y_axis': 'erosion',
        'fixed':  {'temperature': 0.0, 'humidity': 0.0, 'depth': 0.0, 'weirdness': 0.0},
    },
    {
        'name':   'temp_x_weirdness__inland',
        'title':  'Temperature × Weirdness  (mid-inland, neutral humidity, surface)',
        'x_axis': 'temperature',
        'y_axis': 'weirdness',
        'fixed':  {'continentalness': 0.5, 'erosion': 0.0, 'depth': 0.0, 'humidity': 0.0},
    },
]


# ── Data loading ──────────────────────────────────────────────────────────────

def load_planner_overworld_biomes() -> list[str]:
    workbook  = openpyxl.load_workbook(BIOME_PLANNER_FILE)
    worksheet = workbook[BIOME_PLANNER_SHEET]
    headers   = [cell.value for cell in worksheet[1]]
    try:
        overworld_column_index = headers.index(OVERWORLD_PLANNER_HEADER)
    except ValueError:
        print(f'ERROR: column "{OVERWORLD_PLANNER_HEADER}" not found in planner.')
        raise SystemExit(1)
    return [
        str(row[0])
        for row in worksheet.iter_rows(min_row=2, values_only=True)
        if row[0] and ':' in str(row[0]) and row[overworld_column_index] == CHECK_MARK
    ]


def load_overworld_entries(planner_biomes: list[str]) -> list[dict]:
    """
    Loads all overworld climate parameter entries for the selected biomes.
    Includes surface, underground, caves, and deep sections — the depth axis
    in the fitness calculation naturally de-prioritises deep entries when
    visualising at depth=0.
    """
    included_biomes = set(planner_biomes)
    entries: list[dict] = []
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        overworld_sections = data.get('dimensions', {}).get('minecraft:overworld', {})
        for section in ALL_SECTIONS:
            for entry in overworld_sections.get(section, []):
                if entry['biome'] in included_biomes:
                    entries.append({
                        'biome':      entry['biome'],
                        'parameters': entry['parameters'],
                    })
    return entries


# ── Nearest-neighbour computation ─────────────────────────────────────────────

def build_query_grid(
    x_axis:       str,
    y_axis:       str,
    fixed_values: dict[str, float],
) -> np.ndarray:
    """Returns (H, W, 6) array of climate values for a 2D slice."""
    x_lo, x_hi = AXIS_VALUE_RANGES[x_axis]
    y_lo, y_hi = AXIS_VALUE_RANGES[y_axis]
    x_values = np.linspace(x_lo, x_hi, GRID_RESOLUTION)
    y_values = np.linspace(y_lo, y_hi, GRID_RESOLUTION)

    grid = np.zeros((GRID_RESOLUTION, GRID_RESOLUTION, 6))
    for axis_index, axis in enumerate(PARAMETER_AXES):
        if axis == x_axis:
            grid[:, :, axis_index] = x_values[np.newaxis, :]
        elif axis == y_axis:
            grid[:, :, axis_index] = y_values[:, np.newaxis]
        else:
            grid[:, :, axis_index] = fixed_values[axis]
    return grid


def build_parameter_bounds(entries: list[dict]) -> tuple[np.ndarray, np.ndarray]:
    """Returns (lo_array, hi_array) each of shape (N_entries, 6)."""
    n_entries = len(entries)
    lo_array  = np.zeros((n_entries, 6))
    hi_array  = np.zeros((n_entries, 6))
    for entry_index, entry in enumerate(entries):
        params = entry['parameters']
        for axis_index, axis in enumerate(PARAMETER_AXES):
            lo_array[entry_index, axis_index] = params[axis][0]
            hi_array[entry_index, axis_index] = params[axis][1]
    return lo_array, hi_array


def compute_nearest_entry_grid(
    lo_array:   np.ndarray,
    hi_array:   np.ndarray,
    query_grid: np.ndarray,
) -> np.ndarray:
    """
    For each pixel, finds the entry with minimum TerraBlender fitness
    (sum of squared distances to each parameter range).
    Processes entries in chunks of ENTRY_CHUNK_SIZE to bound peak memory.
    Returns (H, W) int32 array of entry indices.
    """
    n_entries = lo_array.shape[0]
    height    = query_grid.shape[0]
    width     = query_grid.shape[1]

    min_fitness   = np.full((height, width), np.inf)
    nearest_entry = np.zeros((height, width), dtype=np.int32)
    row_indices, col_indices = np.meshgrid(
        np.arange(height), np.arange(width), indexing='ij'
    )

    for chunk_start in range(0, n_entries, ENTRY_CHUNK_SIZE):
        chunk_end  = min(chunk_start + ENTRY_CHUNK_SIZE, n_entries)
        chunk_size = chunk_end - chunk_start
        chunk_lo   = lo_array[chunk_start:chunk_end]   # (chunk, 6)
        chunk_hi   = hi_array[chunk_start:chunk_end]   # (chunk, 6)

        chunk_fitness = np.zeros((chunk_size, height, width))
        for axis_index in range(6):
            axis_lo    = chunk_lo[:, axis_index]               # (chunk,)
            axis_hi    = chunk_hi[:, axis_index]               # (chunk,)
            axis_query = query_grid[:, :, axis_index]          # (H, W)

            dist_below = np.maximum(
                axis_lo[:, np.newaxis, np.newaxis] - axis_query[np.newaxis, :, :], 0.0
            )
            dist_above = np.maximum(
                axis_query[np.newaxis, :, :] - axis_hi[:, np.newaxis, np.newaxis], 0.0
            )
            chunk_fitness += np.maximum(dist_below, dist_above) ** 2

        chunk_best_local   = np.argmin(chunk_fitness, axis=0)   # (H, W)
        chunk_best_fitness = chunk_fitness[chunk_best_local, row_indices, col_indices]

        improved = chunk_best_fitness < min_fitness
        min_fitness[improved]   = chunk_best_fitness[improved]
        nearest_entry[improved] = chunk_start + chunk_best_local[improved]

    return nearest_entry


# ── Colour assignment ─────────────────────────────────────────────────────────

def biome_color_rgb(biome_id: str) -> tuple[float, float, float]:
    """Deterministic, visually distinct colour derived from biome ID hash."""
    hash_bytes = hashlib.md5(biome_id.encode()).digest()
    hue        = int.from_bytes(hash_bytes[:2], 'big') / 65536.0
    saturation = 0.55 + (hash_bytes[2] / 255.0) * 0.45
    value      = 0.65 + (hash_bytes[3] / 255.0) * 0.35
    return colorsys.hsv_to_rgb(hue, saturation, value)


def build_rgb_image(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
) -> tuple[np.ndarray, dict[str, tuple]]:
    """Returns (H, W, 3) float RGB image and {biome_id: color} dict."""
    biome_colors  = {e['biome']: biome_color_rgb(e['biome']) for e in entries}
    entry_biomes  = [e['biome'] for e in entries]

    image = np.zeros((*nearest_entry_grid.shape, 3))
    for unique_entry_index in np.unique(nearest_entry_grid):
        biome = entry_biomes[unique_entry_index]
        color = biome_colors[biome]
        mask  = nearest_entry_grid == unique_entry_index
        image[mask] = color

    return image, biome_colors


def compute_biome_coverage(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
) -> dict[str, float]:
    """Returns fraction of pixels won by each biome (sums to ≤ 1.0)."""
    total_pixels = nearest_entry_grid.size
    entry_biomes = [e['biome'] for e in entries]
    coverage: dict[str, float] = {}
    for unique_entry_index in np.unique(nearest_entry_grid):
        biome = entry_biomes[unique_entry_index]
        pixel_count = int(np.sum(nearest_entry_grid == unique_entry_index))
        coverage[biome] = coverage.get(biome, 0) + pixel_count / total_pixels
    return coverage


# ── Rendering ─────────────────────────────────────────────────────────────────

def render_slice(
    slice_config: dict,
    entries:      list[dict],
    lo_array:     np.ndarray,
    hi_array:     np.ndarray,
    output_path:  Path,
) -> dict[str, float]:
    """Renders one 2D slice and saves as PNG. Returns coverage dict."""
    x_axis = slice_config['x_axis']
    y_axis = slice_config['y_axis']
    title  = slice_config['title']

    print(f'  Computing: {title}')
    query_grid         = build_query_grid(x_axis, y_axis, slice_config['fixed'])
    nearest_entry_grid = compute_nearest_entry_grid(lo_array, hi_array, query_grid)
    image, biome_colors = build_rgb_image(nearest_entry_grid, entries)
    coverage            = compute_biome_coverage(nearest_entry_grid, entries)

    top_biomes     = sorted(coverage, key=coverage.__getitem__, reverse=True)
    legend_biomes  = top_biomes[:LEGEND_MAX_BIOMES]
    legend_patches = [
        mpatches.Patch(
            color=biome_colors[biome],
            label=f'{biome}  ({coverage[biome] * 100:.1f}%)',
        )
        for biome in legend_biomes
    ]

    x_lo, x_hi = AXIS_VALUE_RANGES[x_axis]
    y_lo, y_hi = AXIS_VALUE_RANGES[y_axis]

    figure, (map_axis, legend_axis) = plt.subplots(
        1, 2, figsize=(18, 8), gridspec_kw={'width_ratios': [2, 1]}
    )

    map_axis.imshow(
        image,
        origin='lower',
        extent=[x_lo, x_hi, y_lo, y_hi],
        aspect='auto',
        interpolation='nearest',
    )
    map_axis.set_xlabel(x_axis, fontsize=12)
    map_axis.set_ylabel(y_axis, fontsize=12)
    map_axis.set_title(title, fontsize=11)
    map_axis.grid(True, alpha=0.3, linewidth=0.5)

    legend_axis.axis('off')
    legend_axis.legend(
        handles=legend_patches,
        loc='center left',
        fontsize=8,
        title=f'Top {min(LEGEND_MAX_BIOMES, len(coverage))} biomes by coverage',
        title_fontsize=9,
        frameon=True,
    )
    unlabeled_count = max(0, len(coverage) - LEGEND_MAX_BIOMES)
    if unlabeled_count > 0:
        legend_axis.text(
            0.0, 0.02,
            f'+ {unlabeled_count} more biomes (colours present but unlabeled)',
            transform=legend_axis.transAxes,
            fontsize=8,
            color='gray',
        )

    figure.tight_layout()
    figure.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close(figure)
    print(f'    → {output_path}  ({len(coverage)} biomes visible)')
    return coverage


# ── Coverage report ───────────────────────────────────────────────────────────

def print_coverage_report(biome_coverages: dict[str, dict[str, float]]) -> None:
    all_biomes  = sorted({b for coverages in biome_coverages.values() for b in coverages})
    slice_names = list(biome_coverages)
    column_width = 22

    print('\nCoverage % per biome across all slices:')
    header = f'  {"Biome":<50}' + ''.join(f'{name[:column_width - 2]:>{column_width}}' for name in slice_names)
    print(header)
    print('  ' + '-' * (50 + column_width * len(slice_names)))
    for biome in all_biomes:
        row = f'  {biome:<50}'
        for slice_name in slice_names:
            percentage = biome_coverages[slice_name].get(biome, 0.0) * 100
            row += f'{percentage:>{column_width - 1}.1f}%'
        print(row)


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    if not MERGED_DATA_DIR.exists():
        print('ERROR: worldgen_data/merged/ not found. Run merge_biome_parameters.py first.')
        raise SystemExit(1)
    if not BIOME_PLANNER_FILE.exists():
        print(f'ERROR: {BIOME_PLANNER_FILE} not found.')
        raise SystemExit(1)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print('Loading planner overworld biomes...')
    planner_biomes = load_planner_overworld_biomes()
    print(f'  {len(planner_biomes)} biomes selected.')

    print('Loading overworld climate entries...')
    entries = load_overworld_entries(planner_biomes)
    print(f'  {len(entries)} total entries.')

    if not entries:
        print('No entries found — check merged data and planner selections.')
        raise SystemExit(1)

    lo_array, hi_array = build_parameter_bounds(entries)

    biome_coverages: dict[str, dict[str, float]] = {}
    for slice_config in SLICE_CONFIGS:
        output_path = OUTPUT_DIR / f'{slice_config["name"]}.png'
        biome_coverages[slice_config['name']] = render_slice(
            slice_config, entries, lo_array, hi_array, output_path
        )

    print_coverage_report(biome_coverages)
    print(f'\nOutput: {OUTPUT_DIR}/')


main()
