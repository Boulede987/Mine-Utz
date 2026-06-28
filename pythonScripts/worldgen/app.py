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
    humidity range, erosion range, depth range, weirdness range.
    All settings auto-save to worldgen_data/biome_settings.json.

Tabs:
  - World Map:          live 128×128 preview, updates on every weight or constraint change
  - Coverage:           horizontal bar chart of how much area each biome wins
  - Parameter Slices:   interactive parameter-space cross-section
  - Tile Map:           click-to-paint temperature×humidity grid editor
"""

import os
import sys
from pathlib import Path

# Ensure relative paths (worldgen_data/, dimension_biome_planner.xlsx) resolve
# against the project root regardless of where streamlit is invoked from.
os.chdir(Path(__file__).parent.parent.parent)
sys.path.insert(0, str(Path(__file__).parent.parent))

import numpy as np
import streamlit as st

from worldgen.app_coverage import render_coverage_tab
from worldgen.app_data import (
    MAP_SEED, PREVIEW_RESOLUTION,
    WEIGHT_DEFAULT, WEIGHT_MAX, WEIGHT_MIN, WEIGHT_STEP,
)
from worldgen.app_slices import render_slices_tab
from worldgen.app_tile_editor import render_tile_map_tab
from worldgen.app_world_map import render_world_map_tab
from worldgen.biome_constraints import load_settings, save_settings
from worldgen.biome_grid import (
    DEPTH_MAX, DEPTH_MIN, EROSION_MAX, EROSION_MIN, HUMIDITY_MAX, HUMIDITY_MIN,
    SURFACE_DEPTH, TEMPERATURE_MAX, TEMPERATURE_MIN, WEIRDNESS_MAX, WEIRDNESS_MIN,
    ZONE_LAND, ZONE_OCEAN,
    default_zone, generate_grid_entries,
)
from worldgen.biome_loader import load_biome_parameter_defaults, load_planner_overworld_biomes
from worldgen.biome_nearest_neighbour import build_parameter_bounds, compute_nearest_entry_grid
from worldgen.biome_noise import climate_maps_to_query_grid, generate_all_climate_maps

TEMPERATURE_STEP = 0.1
HUMIDITY_STEP    = 0.1
EROSION_STEP     = 0.1
DEPTH_STEP       = 0.05
WEIRDNESS_STEP   = 0.1


# ── Cached data loading ───────────────────────────────────────────────────────

@st.cache_data(show_spinner='Loading biome data...')
def load_all_data() -> dict:
    planner_biomes = load_planner_overworld_biomes()
    preview_query  = climate_maps_to_query_grid(
        generate_all_climate_maps(PREVIEW_RESOLUTION, PREVIEW_RESOLUTION, MAP_SEED)
    )
    biome_defaults = load_biome_parameter_defaults(planner_biomes)
    return {
        'planner_biomes': planner_biomes,
        'preview_query':  preview_query,
        'biome_defaults': biome_defaults,
    }


# ── Sidebar ───────────────────────────────────────────────────────────────────

def _resolved_biome_defaults(biome_id: str, saved: dict, defaults: dict) -> dict:
    """Returns the resolved store_ key → value mapping for one biome.

    Priority: saved settings > actual biome data defaults > full axis range fallback.
    """
    return {
        f'store_bw_{biome_id}':      saved.get('weight', WEIGHT_DEFAULT),
        f'store_zone_{biome_id}':    saved.get('zone', default_zone(biome_id)),
        f'store_temp_{biome_id}':    (
            float(saved.get('temperature_lo', defaults.get('temperature_lo', TEMPERATURE_MIN))),
            float(saved.get('temperature_hi', defaults.get('temperature_hi', TEMPERATURE_MAX))),
        ),
        f'store_hum_{biome_id}':     (
            float(saved.get('humidity_lo', defaults.get('humidity_lo', HUMIDITY_MIN))),
            float(saved.get('humidity_hi', defaults.get('humidity_hi', HUMIDITY_MAX))),
        ),
        f'store_erosion_{biome_id}': (
            float(saved.get('erosion_lo', defaults.get('erosion_lo', EROSION_MIN))),
            float(saved.get('erosion_hi', defaults.get('erosion_hi', EROSION_MAX))),
        ),
        f'store_depth_{biome_id}':   (
            float(saved.get('depth_lo', defaults.get('depth_lo', SURFACE_DEPTH))),
            float(saved.get('depth_hi', defaults.get('depth_hi', SURFACE_DEPTH))),
        ),
        f'store_weird_{biome_id}':   (
            float(saved.get('weirdness_lo', defaults.get('weirdness_lo', WEIRDNESS_MIN))),
            float(saved.get('weirdness_hi', defaults.get('weirdness_hi', WEIRDNESS_MAX))),
        ),
    }


def _init_session_state_from_settings(
    planner_biomes: list[str],
    settings:       dict[str, dict],
    biome_defaults: dict[str, dict],
) -> None:
    """Populates store_ keys on first run only. Widget keys are managed by Streamlit."""
    if st.session_state.get('settings_loaded'):
        return
    for biome_id in planner_biomes:
        resolved = _resolved_biome_defaults(
            biome_id, settings.get(biome_id, {}), biome_defaults.get(biome_id, {})
        )
        for key, value in resolved.items():
            st.session_state.setdefault(key, value)
    st.session_state['settings_loaded'] = True


def _read_biome_from_state(biome_id: str) -> tuple[float, str, tuple, tuple, tuple, tuple, tuple]:
    """Reads biome settings from store_ keys. Safe to call after _init_session_state_from_settings."""
    return (
        st.session_state[f'store_bw_{biome_id}'],
        st.session_state[f'store_zone_{biome_id}'],
        st.session_state[f'store_temp_{biome_id}'],
        st.session_state[f'store_hum_{biome_id}'],
        st.session_state[f'store_erosion_{biome_id}'],
        st.session_state[f'store_depth_{biome_id}'],
        st.session_state[f'store_weird_{biome_id}'],
    )


def _render_biome_expander(biome_id: str) -> tuple[float, str, tuple, tuple, tuple, tuple, tuple]:
    """Renders one biome expander. Returns (weight, zone, temp, humidity, erosion, depth, weirdness).

    Seeded from store_ keys so the widget shows the correct value even after being hidden
    by the search filter (which causes Streamlit to delete the widget key from session_state).
    """
    stored_weight, stored_zone, stored_temp, stored_hum, stored_erosion, stored_depth, stored_weird = (
        _read_biome_from_state(biome_id)
    )

    with st.sidebar.expander(biome_id, expanded=False):
        weight    = st.slider(
            'Weight', WEIGHT_MIN, WEIGHT_MAX, stored_weight, WEIGHT_STEP,
            key=f'bw_{biome_id}',
        )
        zone_index = 0 if stored_zone == ZONE_LAND else 1
        zone       = st.selectbox('Zone', [ZONE_LAND, ZONE_OCEAN], index=zone_index, key=f'zone_{biome_id}')
        temp       = st.slider(
            'Temperature range',
            float(TEMPERATURE_MIN), float(TEMPERATURE_MAX),
            stored_temp, TEMPERATURE_STEP,
            key=f'temp_{biome_id}',
        )
        humidity   = st.slider(
            'Humidity range',
            float(HUMIDITY_MIN), float(HUMIDITY_MAX),
            stored_hum, HUMIDITY_STEP,
            key=f'hum_{biome_id}',
        )
        erosion    = st.slider(
            'Erosion range',
            float(EROSION_MIN), float(EROSION_MAX),
            stored_erosion, EROSION_STEP,
            key=f'erosion_{biome_id}',
        )
        depth      = st.slider(
            'Depth range',
            float(DEPTH_MIN), float(DEPTH_MAX),
            stored_depth, DEPTH_STEP,
            key=f'depth_{biome_id}',
        )
        weirdness  = st.slider(
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


def _reset_all_biome_settings(planner_biomes: list[str]) -> None:
    for biome_id in planner_biomes:
        st.session_state.update(_resolved_biome_defaults(biome_id, {}, {}))
    save_settings({})


def render_sidebar(planner_biomes: list[str]) -> tuple[dict[str, float], dict[str, dict]]:
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
    return {
        biome_id: {'weight': weight, **constraints.get(biome_id, {})}
        for biome_id, weight in biome_weights.items()
    }


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    st.set_page_config(page_title='Biome Layout Visualizer', layout='wide')
    st.title('Biome Layout Visualizer')

    data = load_all_data()
    if not data['planner_biomes']:
        st.error('No biomes selected in planner. Check dimension_biome_planner.xlsx.')
        return

    saved_settings = load_settings()
    _init_session_state_from_settings(data['planner_biomes'], saved_settings, data['biome_defaults'])

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
