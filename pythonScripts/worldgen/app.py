"""
worldgen/app.py
================
Interactive Streamlit app for tuning biome distribution and visualising the result
in real-time without launching Minecraft.

Run from the project root:
    streamlit run pythonScripts/worldgen/app.py

Controls (sidebar):
  - Search bar to filter the biome list.
  - One expander per biome with: weight slider, zone (land/ocean), temperature range,
    humidity range.  All settings auto-save to worldgen_data/biome_settings.json.

Tabs:
  - World Map:          live 128×128 preview, updates on every weight or constraint change
  - Coverage:           horizontal bar chart of how much area each biome wins
  - Parameter Slices:   4 parameter-space cross-sections (recomputed on demand)
  - Tile Map:           click-to-paint temperature×humidity grid editor
"""

import io
import os
import sys
from pathlib import Path

# Ensure relative paths (worldgen_data/, dimension_biome_planner.xlsx) resolve
# against the project root regardless of where streamlit is invoked from.
os.chdir(Path(__file__).parent.parent.parent)
sys.path.insert(0, str(Path(__file__).parent.parent))

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
import plotly.graph_objects as go
import streamlit as st

from worldgen.app_data import (
    AXIS_VALUE_RANGES, EXPORT_RESOLUTION, MAP_SEED, PREVIEW_RESOLUTION,
    SLICE_CONFIGS, SLICE_RESOLUTION,
    WEIGHT_DEFAULT, WEIGHT_MAX, WEIGHT_MIN, WEIGHT_STEP,
)
from worldgen.biome_colour import (
    build_highlighted_image, build_rgb_image,
    compute_biome_coverage, compute_patch_stats,
)
from worldgen.biome_constraints import load_settings, save_settings
from worldgen.biome_grid import (
    DEPTH_MAX, DEPTH_MIN, EROSION_MAX, EROSION_MIN, HUMIDITY_MAX, HUMIDITY_MIN,
    SURFACE_DEPTH, TEMPERATURE_MAX, TEMPERATURE_MIN, WEIRDNESS_MAX, WEIRDNESS_MIN,
    ZONE_LAND, ZONE_OCEAN,
    default_zone, generate_grid_entries,
)
from worldgen.biome_nearest_neighbour import (
    build_parameter_bounds, compute_nearest_entry_grid,
    load_planner_overworld_biomes,
)
from worldgen.biome_noise import climate_maps_to_query_grid, generate_all_climate_maps
from worldgen.constants import PARAMETER_AXES
from worldgen.terrablender_codegen import build_region
from worldgen.app_tile_editor import render_tile_map_tab

TEMPERATURE_STEP      = 0.1
HUMIDITY_STEP         = 0.1
EROSION_STEP          = 0.1
DEPTH_STEP            = 0.05
WEIRDNESS_STEP        = 0.1
SLICE_FIXED_AXIS_STEP = 0.05
FILTER_REGION_JAVA = Path(
    'companion-mods/terrablender-custom-region'
    '/src/main/java/com/mineutze/filtermod/FilterRegion.java'
)


# ── Cached data loading ───────────────────────────────────────────────────────

@st.cache_data(show_spinner='Loading biome data...')
def load_all_data() -> dict:
    """Loads everything that doesn't depend on weights or constraints. Cached once per session."""
    planner_biomes = load_planner_overworld_biomes()
    preview_query  = climate_maps_to_query_grid(
        generate_all_climate_maps(PREVIEW_RESOLUTION, PREVIEW_RESOLUTION, MAP_SEED)
    )
    return {
        'planner_biomes': planner_biomes,
        'preview_query':  preview_query,
    }


@st.cache_data(show_spinner='Generating export-resolution climate maps...')
def load_export_query_grid() -> np.ndarray:
    """Generates the full-resolution query grid. Cached after first export."""
    return climate_maps_to_query_grid(
        generate_all_climate_maps(EXPORT_RESOLUTION, EXPORT_RESOLUTION, MAP_SEED)
    )


# ── Sidebar ───────────────────────────────────────────────────────────────────

def _init_session_state_from_settings(
    planner_biomes: list[str],
    settings:       dict[str, dict],
) -> None:
    """Populates persistent store_ keys from saved settings on first run only.

    Widget keys (bw_, zone_, temp_, hum_) are managed by Streamlit and deleted
    when their widget is not rendered.  The store_ keys are set manually and survive
    across reruns regardless of whether the corresponding widget is visible.
    """
    if st.session_state.get('settings_loaded'):
        return
    for biome_id in planner_biomes:
        saved = settings.get(biome_id, {})
        st.session_state.setdefault(f'store_bw_{biome_id}',   saved.get('weight', WEIGHT_DEFAULT))
        st.session_state.setdefault(f'store_zone_{biome_id}', saved.get('zone', default_zone(biome_id)))
        st.session_state.setdefault(f'store_temp_{biome_id}', (
            float(saved.get('temperature_lo', TEMPERATURE_MIN)),
            float(saved.get('temperature_hi', TEMPERATURE_MAX)),
        ))
        st.session_state.setdefault(f'store_hum_{biome_id}', (
            float(saved.get('humidity_lo', HUMIDITY_MIN)),
            float(saved.get('humidity_hi', HUMIDITY_MAX)),
        ))
        st.session_state.setdefault(f'store_erosion_{biome_id}', (
            float(saved.get('erosion_lo', EROSION_MIN)),
            float(saved.get('erosion_hi', EROSION_MAX)),
        ))
        st.session_state.setdefault(f'store_depth_{biome_id}', (
            float(saved.get('depth_lo', SURFACE_DEPTH)),
            float(saved.get('depth_hi', SURFACE_DEPTH)),
        ))
        st.session_state.setdefault(f'store_weird_{biome_id}', (
            float(saved.get('weirdness_lo', WEIRDNESS_MIN)),
            float(saved.get('weirdness_hi', WEIRDNESS_MAX)),
        ))
    st.session_state['settings_loaded'] = True


def _render_biome_expander(biome_id: str) -> tuple[float, str, tuple, tuple, tuple, tuple]:
    """Renders one biome expander. Returns (weight, zone, temp_range, hum_range, erosion_range, weirdness_range).

    Values are seeded from store_ keys so the widget shows the correct value even
    after being hidden by the search filter (which causes Streamlit to delete the
    widget key from session_state).  The store_ keys are updated after each render
    so they always reflect the latest user interaction.
    """
    stored_weight   = st.session_state.get(f'store_bw_{biome_id}',      WEIGHT_DEFAULT)
    stored_zone     = st.session_state.get(f'store_zone_{biome_id}',     default_zone(biome_id))
    stored_temp     = st.session_state.get(f'store_temp_{biome_id}',     (float(TEMPERATURE_MIN), float(TEMPERATURE_MAX)))
    stored_hum      = st.session_state.get(f'store_hum_{biome_id}',      (float(HUMIDITY_MIN),    float(HUMIDITY_MAX)))
    stored_erosion  = st.session_state.get(f'store_erosion_{biome_id}',  (float(EROSION_MIN),     float(EROSION_MAX)))
    stored_depth    = st.session_state.get(f'store_depth_{biome_id}',    (float(SURFACE_DEPTH),   float(SURFACE_DEPTH)))
    stored_weird    = st.session_state.get(f'store_weird_{biome_id}',    (float(WEIRDNESS_MIN),   float(WEIRDNESS_MAX)))

    with st.sidebar.expander(biome_id, expanded=False):
        weight = st.slider(
            'Weight', WEIGHT_MIN, WEIGHT_MAX, stored_weight, WEIGHT_STEP,
            key=f'bw_{biome_id}',
        )
        zone_index = 0 if stored_zone == ZONE_LAND else 1
        zone     = st.selectbox('Zone', [ZONE_LAND, ZONE_OCEAN], index=zone_index, key=f'zone_{biome_id}')
        temp     = st.slider(
            'Temperature range',
            float(TEMPERATURE_MIN), float(TEMPERATURE_MAX),
            stored_temp, TEMPERATURE_STEP,
            key=f'temp_{biome_id}',
        )
        humidity = st.slider(
            'Humidity range',
            float(HUMIDITY_MIN), float(HUMIDITY_MAX),
            stored_hum, HUMIDITY_STEP,
            key=f'hum_{biome_id}',
        )
        erosion = st.slider(
            'Erosion range',
            float(EROSION_MIN), float(EROSION_MAX),
            stored_erosion, EROSION_STEP,
            key=f'erosion_{biome_id}',
        )
        depth = st.slider(
            'Depth range',
            float(DEPTH_MIN), float(DEPTH_MAX),
            stored_depth, DEPTH_STEP,
            key=f'depth_{biome_id}',
        )
        weirdness = st.slider(
            'Weirdness range',
            float(WEIRDNESS_MIN), float(WEIRDNESS_MAX),
            stored_weird, WEIRDNESS_STEP,
            key=f'weird_{biome_id}',
        )

    st.session_state[f'store_bw_{biome_id}']      = weight
    st.session_state[f'store_zone_{biome_id}']     = zone
    st.session_state[f'store_temp_{biome_id}']     = temp
    st.session_state[f'store_hum_{biome_id}']      = humidity
    st.session_state[f'store_erosion_{biome_id}']  = erosion
    st.session_state[f'store_depth_{biome_id}']    = depth
    st.session_state[f'store_weird_{biome_id}']    = weirdness

    return weight, zone, temp, humidity, erosion, depth, weirdness


def _read_biome_from_state(biome_id: str) -> tuple[float, str, tuple, tuple, tuple, tuple, tuple]:
    """Reads biome settings from store_ keys for biomes not currently rendered."""
    weight    = st.session_state.get(f'store_bw_{biome_id}',      WEIGHT_DEFAULT)
    zone      = st.session_state.get(f'store_zone_{biome_id}',     default_zone(biome_id))
    temp      = st.session_state.get(f'store_temp_{biome_id}',     (float(TEMPERATURE_MIN), float(TEMPERATURE_MAX)))
    humidity  = st.session_state.get(f'store_hum_{biome_id}',      (float(HUMIDITY_MIN),    float(HUMIDITY_MAX)))
    erosion   = st.session_state.get(f'store_erosion_{biome_id}',  (float(EROSION_MIN),     float(EROSION_MAX)))
    depth     = st.session_state.get(f'store_depth_{biome_id}',    (float(SURFACE_DEPTH),   float(SURFACE_DEPTH)))
    weirdness = st.session_state.get(f'store_weird_{biome_id}',    (float(WEIRDNESS_MIN),   float(WEIRDNESS_MAX)))
    return weight, zone, temp, humidity, erosion, depth, weirdness


def _reset_all_biome_settings(planner_biomes: list[str]) -> None:
    for biome_id in planner_biomes:
        st.session_state[f'store_bw_{biome_id}']      = WEIGHT_DEFAULT
        st.session_state[f'store_zone_{biome_id}']     = default_zone(biome_id)
        st.session_state[f'store_temp_{biome_id}']     = (float(TEMPERATURE_MIN), float(TEMPERATURE_MAX))
        st.session_state[f'store_hum_{biome_id}']      = (float(HUMIDITY_MIN),    float(HUMIDITY_MAX))
        st.session_state[f'store_erosion_{biome_id}']  = (float(EROSION_MIN),   float(EROSION_MAX))
        st.session_state[f'store_depth_{biome_id}']    = (float(SURFACE_DEPTH), float(SURFACE_DEPTH))
        st.session_state[f'store_weird_{biome_id}']    = (float(WEIRDNESS_MIN), float(WEIRDNESS_MAX))
    save_settings({})


def render_sidebar(planner_biomes: list[str]) -> tuple[dict[str, float], dict[str, dict]]:
    """
    Renders flat searchable biome list with per-biome weight and constraint controls.
    Returns (biome_weights, constraints).
    """
    st.sidebar.title('Biomes')

    if st.sidebar.button('Reset all'):
        _reset_all_biome_settings(planner_biomes)
        st.rerun()

    search_query = st.sidebar.text_input('Search', placeholder='filter biomes…').lower()

    biome_weights: dict[str, float] = {}
    constraints:   dict[str, dict]  = {}

    for biome_id in sorted(planner_biomes):
        is_visible = not search_query or search_query in biome_id.lower()
        if is_visible:
            weight, zone, temp, humidity, erosion, depth, weirdness = _render_biome_expander(biome_id)
        else:
            weight, zone, temp, humidity, erosion, depth, weirdness = _read_biome_from_state(biome_id)

        biome_weights[biome_id] = weight
        constraints[biome_id]   = {
            'zone':           zone,
            'temperature_lo': temp[0],
            'temperature_hi': temp[1],
            'humidity_lo':    humidity[0],
            'humidity_hi':    humidity[1],
            'erosion_lo':     erosion[0],
            'erosion_hi':     erosion[1],
            'depth_lo':       depth[0],
            'depth_hi':       depth[1],
            'weirdness_lo':   weirdness[0],
            'weirdness_hi':   weirdness[1],
        }

    return biome_weights, constraints


def _collect_settings(
    biome_weights: dict[str, float],
    constraints:   dict[str, dict],
) -> dict[str, dict]:
    """Merges weights and constraints into a single dict for JSON persistence."""
    return {
        biome_id: {'weight': weight, **constraints.get(biome_id, {})}
        for biome_id, weight in biome_weights.items()
    }


# ── World Map tab ─────────────────────────────────────────────────────────────

def _grid_entries_to_region_params(entries: list[dict]) -> dict[str, list[dict]]:
    """Converts grid entries to the format build_region() expects: {biome_id: [param_dict, ...]}."""
    region_params: dict[str, list[dict]] = {}
    for entry in entries:
        param = {**entry['parameters'], 'offset': 0.0}
        region_params.setdefault(entry['biome'], []).append(param)
    return region_params

def render_preview_map_plotly(
    nearest_entry_grid:   np.ndarray,
    entries:              list[dict],
    highlighted_biome_id: str | None,
    biome_colors:         dict[str, tuple],
) -> None:
    """Renders biome map as interactive Plotly chart with per-pixel hover tooltip."""
    if highlighted_biome_id:
        image = build_highlighted_image(nearest_entry_grid, entries, highlighted_biome_id, biome_colors)
    else:
        image, _ = build_rgb_image(nearest_entry_grid, entries)

    entry_biomes    = [entry['biome'] for entry in entries]
    biome_name_grid = np.array(entry_biomes)[nearest_entry_grid]
    rgb_uint8       = (image * 255).astype(np.uint8)

    figure = go.Figure(go.Image(
        z=rgb_uint8,
        customdata=biome_name_grid,
        hovertemplate='%{customdata}<extra></extra>',
    ))
    figure.update_layout(
        margin=dict(l=0, r=0, t=0, b=0),
        height=520,
        xaxis=dict(visible=False),
        yaxis=dict(visible=False),
    )
    st.plotly_chart(figure, use_container_width=True)


def render_export_section(
    data:          dict,
    biome_weights: dict[str, float],
    constraints:   dict[str, dict],
    entries:       list[dict],
) -> None:
    st.divider()
    col_png_btn, col_png_dl = st.columns([1, 2])

    with col_png_btn:
        if st.button(f'Export {EXPORT_RESOLUTION}×{EXPORT_RESOLUTION} PNG'):
            with st.spinner('Computing full-resolution map...'):
                export_entries       = generate_grid_entries(data['planner_biomes'], biome_weights, constraints)
                export_lo, export_hi = build_parameter_bounds(export_entries)
                nearest_full         = compute_nearest_entry_grid(
                    export_lo, export_hi, load_export_query_grid()
                )
                st.session_state['export_png'] = _render_map_to_png_bytes(nearest_full, export_entries)

    with col_png_dl:
        if 'export_png' in st.session_state:
            st.download_button(
                label='Download PNG',
                data=st.session_state['export_png'],
                file_name='world_map.png',
                mime='image/png',
            )

    col_java_btn, col_java_status = st.columns([1, 2])

    with col_java_btn:
        if st.button('Export to FilterRegion.java'):
            if not FILTER_REGION_JAVA.parent.exists():
                st.session_state['java_export_error'] = str(FILTER_REGION_JAVA.parent)
                st.session_state.pop('java_export_ok', None)
            else:
                region_params   = _grid_entries_to_region_params(entries)
                biome_ids       = sorted(region_params.keys())
                total_entries   = sum(len(params) for params in region_params.values())
                build_region(biome_ids, region_params, 'FilterRegion', 'OVERWORLD', FILTER_REGION_JAVA)
                st.session_state['java_export_ok']    = (len(biome_ids), total_entries)
                st.session_state.pop('java_export_error', None)

    with col_java_status:
        if 'java_export_ok' in st.session_state:
            biome_count, entry_count = st.session_state['java_export_ok']
            st.success(f'{biome_count} biomes, {entry_count} entries → FilterRegion.java. Rebuild mod in IntelliJ.')
        elif 'java_export_error' in st.session_state:
            st.error(f'Companion mod not found: {st.session_state["java_export_error"]}')


def _render_map_to_png_bytes(nearest_entry_grid: np.ndarray, entries: list[dict]) -> bytes:
    image, _ = build_rgb_image(nearest_entry_grid, entries)
    figure, axis = plt.subplots(figsize=(10, 10))
    axis.imshow(image, origin='upper', interpolation='nearest', aspect='equal')
    axis.axis('off')
    figure.tight_layout(pad=0)
    buffer = io.BytesIO()
    figure.savefig(buffer, format='png', dpi=150, bbox_inches='tight')
    plt.close(figure)
    buffer.seek(0)
    return buffer.read()


def render_world_map_tab(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
    data:               dict,
    biome_weights:      dict[str, float],
    constraints:        dict[str, dict],
) -> None:
    image, biome_colors = build_rgb_image(nearest_entry_grid, entries)

    entry_biomes      = [entry['biome'] for entry in entries]
    visible_biome_ids = sorted({entry_biomes[idx] for idx in np.unique(nearest_entry_grid)})

    highlight_label      = st.selectbox(
        'Highlight biome (rest dimmed)',
        options=['— none —'] + visible_biome_ids,
        key='highlight_biome',
    )
    highlighted_biome_id = None if highlight_label == '— none —' else highlight_label

    render_preview_map_plotly(nearest_entry_grid, entries, highlighted_biome_id, biome_colors)
    render_export_section(data, biome_weights, constraints, entries)


# ── Coverage tab ──────────────────────────────────────────────────────────────

def _render_coverage_chart(
    sorted_biomes: list[str],
    coverage_pcts: list[float],
    biome_colors:  dict[str, tuple],
) -> None:
    colors = [biome_colors.get(biome, (0.5, 0.5, 0.5)) for biome in sorted_biomes]
    figure, axis = plt.subplots(figsize=(10, max(4, len(sorted_biomes) * 0.28)))
    axis.barh(sorted_biomes, coverage_pcts, color=colors)
    axis.set_xlabel('Coverage (%)', fontsize=10)
    axis.invert_yaxis()
    axis.tick_params(axis='y', labelsize=8)
    figure.tight_layout()
    st.pyplot(figure, use_container_width=True)
    plt.close(figure)


def _render_patch_stats_guide() -> None:
    with st.expander('Column guide'):
        st.markdown(
            '| Column | What it tells you |\n'
            '|---|---|\n'
            '| **Coverage %** | Total map area this biome wins |\n'
            '| **Patches** | Number of disconnected regions |\n'
            '| **Median patch %** | Typical patch size — half are smaller, half bigger |\n'
            '| **Largest patch %** | Biggest single contiguous region |\n'
            '| **Avg patch %** | Mean patch size (Coverage ÷ Patches) |\n'
            '| **Concentration** | Largest patch ÷ Coverage. Near **1.0** = one big blob. Near **0** = many tiny fragments. |'
        )


def _build_patch_stats_rows(
    sorted_biomes: list[str],
    coverage:      dict[str, float],
    patch_stats:   dict[str, dict],
) -> dict:
    coverage_pcts  = [round(coverage[biome] * 100, 2) for biome in sorted_biomes]
    patch_counts   = [patch_stats.get(biome, {}).get('patch_count', 0) for biome in sorted_biomes]
    median_pcts    = [round(patch_stats.get(biome, {}).get('median_patch_pct', 0.0), 3) for biome in sorted_biomes]
    largest_pcts   = [round(patch_stats.get(biome, {}).get('largest_patch_pct', 0.0), 2) for biome in sorted_biomes]
    avg_pcts       = [
        round(cov / patches, 3) if patches > 0 else 0.0
        for cov, patches in zip(coverage_pcts, patch_counts)
    ]
    concentrations = [
        round(largest / cov, 2) if cov > 0 else 0.0
        for largest, cov in zip(largest_pcts, coverage_pcts)
    ]
    return {
        'Biome':           sorted_biomes,
        'Coverage %':      coverage_pcts,
        'Patches':         patch_counts,
        'Median patch %':  median_pcts,
        'Largest patch %': largest_pcts,
        'Avg patch %':     avg_pcts,
        'Concentration':   concentrations,
    }


def _render_patch_stats_table(
    sorted_biomes: list[str],
    coverage:      dict[str, float],
    patch_stats:   dict[str, dict],
) -> None:
    st.subheader('Patch Fragmentation')
    _render_patch_stats_guide()
    st.dataframe(_build_patch_stats_rows(sorted_biomes, coverage, patch_stats),
                 use_container_width=True, hide_index=True)


def render_coverage_tab(nearest_entry_grid: np.ndarray, entries: list[dict]) -> None:
    _, biome_colors = build_rgb_image(nearest_entry_grid, entries)
    coverage        = compute_biome_coverage(nearest_entry_grid, entries)
    patch_stats     = compute_patch_stats(nearest_entry_grid, entries)

    sorted_biomes = sorted(coverage, key=coverage.__getitem__, reverse=True)
    coverage_pcts = [coverage[biome] * 100 for biome in sorted_biomes]

    _render_coverage_chart(sorted_biomes, coverage_pcts, biome_colors)
    st.divider()
    _render_patch_stats_table(sorted_biomes, coverage, patch_stats)


# ── Slices tab ────────────────────────────────────────────────────────────────

def build_slice_query_grid(
    x_axis:       str,
    y_axis:       str,
    fixed_values: dict[str, float],
) -> np.ndarray:
    x_lo, x_hi = AXIS_VALUE_RANGES[x_axis]
    y_lo, y_hi = AXIS_VALUE_RANGES[y_axis]
    x_values   = np.linspace(x_lo, x_hi, SLICE_RESOLUTION)
    y_values   = np.linspace(y_lo, y_hi, SLICE_RESOLUTION)

    grid = np.zeros((SLICE_RESOLUTION, SLICE_RESOLUTION, 6))
    for axis_index, axis in enumerate(PARAMETER_AXES):
        if axis == x_axis:
            grid[:, :, axis_index] = x_values[np.newaxis, :]
        elif axis == y_axis:
            grid[:, :, axis_index] = y_values[:, np.newaxis]
        else:
            grid[:, :, axis_index] = fixed_values[axis]
    return grid


def _build_slice_hover_grid(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
    x_axis_label:       str,
    y_axis_label:       str,
    x_lo:               float,
    x_hi:               float,
    y_lo:               float,
    y_hi:               float,
) -> np.ndarray:
    """Builds a string grid with biome name and parameter coords per pixel.

    Row 0 corresponds to y_hi (image is vertically flipped for Plotly's top-left origin).
    """
    entry_biomes    = [entry['biome'] for entry in entries]
    biome_name_grid = np.array(entry_biomes)[nearest_entry_grid]

    # After vertical flip, row 0 = y_hi
    x_values = np.linspace(x_lo, x_hi, SLICE_RESOLUTION)
    y_values = np.linspace(y_hi, y_lo, SLICE_RESOLUTION)

    hover_grid = np.empty_like(biome_name_grid)
    for row in range(SLICE_RESOLUTION):
        y_val = y_values[row]
        for col in range(SLICE_RESOLUTION):
            hover_grid[row, col] = (
                f'{biome_name_grid[SLICE_RESOLUTION - 1 - row, col]}'
                f'<br>{x_axis_label}={x_values[col]:.2f}'
                f'  {y_axis_label}={y_val:.2f}'
            )
    return hover_grid


def render_single_slice(
    slice_config: dict,
    lo_array:     np.ndarray,
    hi_array:     np.ndarray,
    entries:      list[dict],
) -> None:
    query_grid         = build_slice_query_grid(
        slice_config['x_axis'], slice_config['y_axis'], slice_config['fixed']
    )
    nearest_entry_grid = compute_nearest_entry_grid(lo_array, hi_array, query_grid)
    image, _           = build_rgb_image(nearest_entry_grid, entries)

    x_lo, x_hi = AXIS_VALUE_RANGES[slice_config['x_axis']]
    y_lo, y_hi = AXIS_VALUE_RANGES[slice_config['y_axis']]

    # go.Image has top-left origin; our data is bottom-up — flip rows.
    rgb_uint8  = (image * 255).astype(np.uint8)[::-1]
    hover_grid = _build_slice_hover_grid(
        nearest_entry_grid,
        entries,
        slice_config['x_axis'], slice_config['y_axis'],
        x_lo, x_hi, y_lo, y_hi,
    )

    n_ticks    = 5
    tick_pixel = np.linspace(0, SLICE_RESOLUTION - 1, n_ticks)
    figure = go.Figure(go.Image(
        z=rgb_uint8,
        customdata=hover_grid,
        hovertemplate='%{customdata}<extra></extra>',
    ))
    figure.update_layout(
        title=dict(text=slice_config['title'], font=dict(size=11)),
        margin=dict(l=50, r=10, t=40, b=50),
        height=360,
        xaxis=dict(
            title=slice_config['x_axis'],
            tickvals=tick_pixel,
            ticktext=[f'{v:.2f}' for v in np.linspace(x_lo, x_hi, n_ticks)],
        ),
        yaxis=dict(
            title=slice_config['y_axis'],
            tickvals=tick_pixel,
            ticktext=[f'{v:.2f}' for v in np.linspace(y_hi, y_lo, n_ticks)],
        ),
    )
    st.plotly_chart(figure, use_container_width=True)


def _render_axis_selectors() -> tuple[str, str]:
    col_x, col_y  = st.columns(2)
    x_axis        = col_x.selectbox('X axis', PARAMETER_AXES, index=0, key='slice_x_axis')
    y_default_idx = (PARAMETER_AXES.index(x_axis) + 1) % len(PARAMETER_AXES)
    y_axis        = col_y.selectbox('Y axis', PARAMETER_AXES, index=y_default_idx, key='slice_y_axis')
    return x_axis, y_axis


def _render_fixed_axis_sliders(x_axis: str, y_axis: str) -> dict[str, float]:
    fixed_axes   = [axis for axis in PARAMETER_AXES if axis not in (x_axis, y_axis)]
    fixed_values: dict[str, float] = {}
    columns      = st.columns(len(fixed_axes))
    for column, axis in zip(columns, fixed_axes):
        axis_lo, axis_hi  = AXIS_VALUE_RANGES[axis]
        axis_midpoint     = round((axis_lo + axis_hi) / 2.0, 2)
        fixed_values[axis] = column.slider(
            axis,
            float(axis_lo), float(axis_hi), float(axis_midpoint), SLICE_FIXED_AXIS_STEP,
            key=f'slice_fixed_{axis}',
        )
    return fixed_values


def render_slices_tab(
    preview_lo: np.ndarray,
    preview_hi: np.ndarray,
    entries:    list[dict],
) -> None:
    st.caption(f'Interactive parameter-space slice at {SLICE_RESOLUTION}×{SLICE_RESOLUTION}.')
    x_axis, y_axis = _render_axis_selectors()
    if x_axis == y_axis:
        st.error('X and Y axes must be different.')
        return
    fixed_values = _render_fixed_axis_sliders(x_axis, y_axis)
    render_single_slice(
        {'x_axis': x_axis, 'y_axis': y_axis, 'fixed': fixed_values, 'title': f'{x_axis} × {y_axis}'},
        preview_lo, preview_hi, entries,
    )


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    st.set_page_config(page_title='Biome Layout Visualizer', layout='wide')
    st.title('Biome Layout Visualizer')

    data = load_all_data()
    if not data['planner_biomes']:
        st.error('No biomes selected in planner. Check dimension_biome_planner.xlsx.')
        return

    saved_settings = load_settings()
    _init_session_state_from_settings(data['planner_biomes'], saved_settings)

    biome_weights, constraints = render_sidebar(data['planner_biomes'])

    entries                = generate_grid_entries(data['planner_biomes'], biome_weights, constraints)
    preview_lo, preview_hi = build_parameter_bounds(entries)
    nearest_preview        = compute_nearest_entry_grid(preview_lo, preview_hi, data['preview_query'])

    save_settings(_collect_settings(biome_weights, constraints))

    map_tab, coverage_tab, slices_tab, tile_map_tab = st.tabs(
        ['World Map', 'Coverage', 'Parameter Slices', 'Tile Map']
    )

    with map_tab:
        render_world_map_tab(nearest_preview, entries, data, biome_weights, constraints)
    with coverage_tab:
        render_coverage_tab(nearest_preview, entries)
    with slices_tab:
        render_slices_tab(preview_lo, preview_hi, entries)
    with tile_map_tab:
        render_tile_map_tab(data['planner_biomes'])


main()
