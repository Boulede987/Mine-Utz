"""
worldgen/visualize_biome_layout.py
====================================
Renders 2D parameter-space slices of the 6D TerraBlender climate space, showing
which biome wins nearest-neighbour at each query point. Outputs PNG files for
rapid iteration without launching the game.

Each slice fixes 4 axes at representative values and varies the remaining 2,
producing a colour map where each region shows which biome dominates there.

Usage:
    python pythonScripts/worldgen/visualize_biome_layout.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
sys.stdout.reconfigure(encoding='utf-8')

try:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import matplotlib.patches as mpatches
    import numpy as np
except ImportError as missing:
    print(f'ERROR: missing dependency — {missing}')
    print('Run: pip install numpy matplotlib')
    raise SystemExit(1)

from worldgen.biome_colour import biome_color_rgb, build_rgb_image, compute_biome_coverage
from worldgen.biome_nearest_neighbour import (
    build_parameter_bounds, compute_nearest_entry_grid,
    load_overworld_entries, load_planner_overworld_biomes,
    MERGED_DATA_DIR,
)
from worldgen.constants import BIOME_PLANNER_FILE, PARAMETER_AXES

AXIS_VALUE_RANGES = {
    'temperature':     (-1.5,  1.5),
    'humidity':        (-1.0,  1.0),
    'continentalness': (-1.1,  1.0),
    'erosion':         (-1.0,  1.0),
    'depth':           (-1.0,  1.5),
    'weirdness':       (-1.0,  1.0),
}

GRID_RESOLUTION   = 200
LEGEND_MAX_BIOMES = 20
OUTPUT_DIR        = Path('worldgen_data') / 'visualizations'

# Each entry fixes 4 axes at representative values and varies the remaining 2.
# Depth is fixed at 0.0 (surface) throughout.
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


def build_slice_query_grid(x_axis: str, y_axis: str, fixed_values: dict[str, float]) -> np.ndarray:
    """Returns (H, W, 6) array of climate values for a 2D parameter-space slice."""
    x_lo, x_hi = AXIS_VALUE_RANGES[x_axis]
    y_lo, y_hi = AXIS_VALUE_RANGES[y_axis]
    x_values   = np.linspace(x_lo, x_hi, GRID_RESOLUTION)
    y_values   = np.linspace(y_lo, y_hi, GRID_RESOLUTION)

    grid = np.zeros((GRID_RESOLUTION, GRID_RESOLUTION, 6))
    for axis_index, axis in enumerate(PARAMETER_AXES):
        if axis == x_axis:
            grid[:, :, axis_index] = x_values[np.newaxis, :]
        elif axis == y_axis:
            grid[:, :, axis_index] = y_values[:, np.newaxis]
        else:
            grid[:, :, axis_index] = fixed_values[axis]
    return grid


def build_legend_patches(
    coverage:     dict[str, float],
    biome_colors: dict[str, tuple],
    max_biomes:   int,
) -> list:
    top_biomes = sorted(coverage, key=coverage.__getitem__, reverse=True)[:max_biomes]
    return [
        mpatches.Patch(
            color=biome_colors[biome],
            label=f'{biome}  ({coverage[biome] * 100:.1f}%)',
        )
        for biome in top_biomes
    ]


def render_slice(
    slice_config: dict,
    entries:      list[dict],
    lo_array:     np.ndarray,
    hi_array:     np.ndarray,
    output_path:  Path,
) -> dict[str, float]:
    """Renders one parameter-space slice to a PNG. Returns coverage dict."""
    x_axis = slice_config['x_axis']
    y_axis = slice_config['y_axis']

    print(f'  Computing: {slice_config["title"]}')
    query_grid          = build_slice_query_grid(x_axis, y_axis, slice_config['fixed'])
    nearest_entry_grid  = compute_nearest_entry_grid(lo_array, hi_array, query_grid)
    image, biome_colors = build_rgb_image(nearest_entry_grid, entries)
    coverage            = compute_biome_coverage(nearest_entry_grid, entries)

    figure, (map_axis, legend_axis) = plt.subplots(
        1, 2, figsize=(18, 8), gridspec_kw={'width_ratios': [2, 1]}
    )
    x_lo, x_hi = AXIS_VALUE_RANGES[x_axis]
    y_lo, y_hi = AXIS_VALUE_RANGES[y_axis]

    map_axis.imshow(image, origin='lower', extent=[x_lo, x_hi, y_lo, y_hi],
                    aspect='auto', interpolation='nearest')
    map_axis.set_xlabel(x_axis, fontsize=12)
    map_axis.set_ylabel(y_axis, fontsize=12)
    map_axis.set_title(slice_config['title'], fontsize=11)
    map_axis.grid(True, alpha=0.3, linewidth=0.5)

    legend_patches = build_legend_patches(coverage, biome_colors, LEGEND_MAX_BIOMES)
    legend_axis.axis('off')
    legend_axis.legend(handles=legend_patches, loc='center left', fontsize=8,
                       title=f'Top {len(legend_patches)} biomes by coverage',
                       title_fontsize=9, frameon=True)
    unlabeled_count = max(0, len(coverage) - LEGEND_MAX_BIOMES)
    if unlabeled_count > 0:
        legend_axis.text(0.0, 0.02, f'+ {unlabeled_count} more biomes (unlabeled)',
                         transform=legend_axis.transAxes, fontsize=8, color='gray')

    figure.tight_layout()
    figure.savefig(output_path, dpi=150, bbox_inches='tight')
    plt.close(figure)
    print(f'    → {output_path}  ({len(coverage)} biomes visible)')
    return coverage


def print_coverage_report(biome_coverages: dict[str, dict[str, float]]) -> None:
    all_biomes  = sorted({b for coverages in biome_coverages.values() for b in coverages})
    slice_names = list(biome_coverages)
    column_width = 22

    print('\nCoverage % per biome across all slices:')
    header = f'  {"Biome":<50}' + ''.join(f'{name[:column_width]:>{column_width}}' for name in slice_names)
    print(header)
    print('  ' + '-' * (50 + column_width * len(slice_names)))
    for biome in all_biomes:
        row = f'  {biome:<50}'
        for slice_name in slice_names:
            row += f'{biome_coverages[slice_name].get(biome, 0.0) * 100:>{column_width - 1}.1f}%'
        print(row)


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


if __name__ == '__main__':
    main()
