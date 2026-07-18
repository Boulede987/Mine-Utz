"""
worldgen/app_world_paint.py
============================
Paint-on-map biome assignment.

Renders the world preview on an HTML canvas (via a declare_component iframe) so
click events are handled natively in JavaScript — no Plotly pan/select conflict.
Each painted cell's climate values become exact point-anchor TerraBlender entries,
placed before weight-based entries so they win nearest-neighbour ties.
"""

import base64
import io
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
import streamlit as st
import streamlit.components.v1 as components

from worldgen.biome_colour import biome_color_rgb, build_rgb_image
from worldgen.biome_grid import (
    EROSION_MIN, EROSION_MAX,
    LAND_CONTINENTALNESS_LO, LAND_CONTINENTALNESS_HI,
    OCEAN_CONTINENTALNESS_LO, OCEAN_CONTINENTALNESS_HI,
    SURFACE_DEPTH,
    WEIRDNESS_MIN, WEIRDNESS_MAX,
    ZONE_LAND, ZONE_OCEAN,
    default_zone,
)
from worldgen.constants import PAINT_ASSIGNMENTS_FILE, PAINT_SEEDS_FILE, PARAMETER_AXES

PAINT_ERASE_BRUSH     = '__erase__'
PAINT_DOWNSAMPLE_STEP = 4
PAINT_CHART_HEIGHT_PX = 524

_TEMP_AXIS_IDX = PARAMETER_AXES.index('temperature')
_HUM_AXIS_IDX  = PARAMETER_AXES.index('humidity')


def paint_covers_full_map(assignments: dict[str, str], preview_height: int, preview_width: int) -> bool:
    """True when every scatter-grid cell has been assigned a biome."""
    step          = PAINT_DOWNSAMPLE_STEP
    half          = step // 2
    expected_rows = range(half, preview_height, step)
    expected_cols = range(half, preview_width,  step)
    total_cells   = len(expected_rows) * len(expected_cols)
    return len(assignments) >= total_cells

_COMPONENT_DIR  = Path(__file__).parent / 'paint_component'
_paint_component = components.declare_component('paint_map', path=str(_COMPONENT_DIR))


# ── Persistence ───────────────────────────────────────────────────────────────

def load_paint_assignments() -> dict[str, str]:
    """Returns {'{row},{col}': biome_id} or empty dict."""
    if not PAINT_ASSIGNMENTS_FILE.exists():
        return {}
    return json.loads(PAINT_ASSIGNMENTS_FILE.read_text(encoding='utf-8'))


def save_paint_assignments(assignments: dict[str, str]) -> None:
    PAINT_ASSIGNMENTS_FILE.parent.mkdir(parents=True, exist_ok=True)
    PAINT_ASSIGNMENTS_FILE.write_text(
        json.dumps(assignments, indent=2, ensure_ascii=False),
        encoding='utf-8',
    )


def load_paint_seeds() -> list[list] | None:
    """Returns [[temp, hum, biome_id], ...] from auto-layout, or None if not present.

    Returns None for the old world-space format (row/col integers >> 1) so stale
    files are silently discarded rather than producing garbage entries.
    """
    if not PAINT_SEEDS_FILE.exists():
        return None
    seeds = json.loads(PAINT_SEEDS_FILE.read_text(encoding='utf-8'))
    if not seeds:
        return None
    if abs(seeds[0][0]) > 1.5:
        return None
    return seeds


def save_paint_seeds(seeds: list) -> None:
    PAINT_SEEDS_FILE.parent.mkdir(parents=True, exist_ok=True)
    PAINT_SEEDS_FILE.write_text(json.dumps(seeds, indent=2), encoding='utf-8')


def delete_paint_seeds() -> None:
    if PAINT_SEEDS_FILE.exists():
        PAINT_SEEDS_FILE.unlink()


# ── Entry generation ──────────────────────────────────────────────────────────

def paint_to_entries(
    assignments:   dict[str, str],
    preview_query: np.ndarray,
    constraints:   dict[str, dict],
) -> list[dict]:
    """Converts painted cell assignments to TerraBlender entries.

    One point anchor per cell (temperature + humidity at cell centre).  Point
    anchors produce a smooth Voronoi partition in climate space → natural curved
    biome borders, unlike range entries which create rectangular partitions.

    Continentalness uses the biome's zone range so land biomes only win on land
    pixels and ocean biomes only win on ocean pixels.  Erosion and weirdness span
    their full range to avoid over-constraining.
    """
    height, width = preview_query.shape[:2]
    entries = []

    for key, biome_id in assignments.items():
        row, col = map(int, key.split(','))
        if not (0 <= row < height and 0 <= col < width):
            continue
        climate = preview_query[row, col]
        biome_c = constraints.get(biome_id, {})
        zone    = biome_c.get('zone', default_zone(biome_id))
        entries.append({
            'biome':      biome_id,
            'parameters': _anchor_parameters(float(climate[_TEMP_AXIS_IDX]),
                                             float(climate[_HUM_AXIS_IDX]),
                                             zone, biome_c),
        })

    return entries


def climate_seeds_to_entries(
    climate_seeds: list[list],
    constraints:   dict[str, dict],
) -> list[dict]:
    """One point anchor per K-means cluster centre.

    Seeds are [temp, hum, biome_id] triples in climate space — no preview_query
    lookup needed.  These are the canonical TerraBlender entries after auto-layout.

    DO NOT collapse these into one wide span entry per biome.  A single
    Climate.Parameter.span covering a biome's full temperature × humidity extent
    makes that biome win a huge region of climate space with distance = 0
    everywhere inside.  TerraBlender resolves ties at chunk boundaries, so any
    two biomes whose spans overlap produce a hard edge exactly at the chunk border
    → one chunk = one biome = a grid of rectangular colour blocks.  Many smaller
    point anchors produce a smooth Voronoi partition in climate space where biome
    borders follow the actual noise isoline rather than chunk edges.
    """
    entries = []
    for seed in climate_seeds:
        temp, hum, biome_id = float(seed[0]), float(seed[1]), seed[2]
        biome_c = constraints.get(biome_id, {})
        zone    = biome_c.get('zone', default_zone(biome_id))
        entries.append({
            'biome':      biome_id,
            'parameters': _anchor_parameters(temp, hum, zone, biome_c),
        })
    return entries


def _anchor_parameters(temp: float, hum: float, zone: str, biome_c: dict | None = None) -> dict:
    """Point anchor in temperature×humidity; zone, erosion, depth, weirdness from biome constraints.

    Land cont = [0.03, 1.0].  Do NOT lower this to -0.19 to cover the coast strip.
    At deep ocean (cont = -0.5), lowering lo from 0.03 → -0.19 cuts the zone penalty
    from 0.28 to 0.096 — 3× weaker — letting land biomes win when ocean clusters are sparse.
    The gap -0.19 → 0.03 (coast + near-inland) falls to nearest biome: land wins for cont > -0.08
    because ocean anchor (HI = -0.19) has larger distance than land anchor (LO = 0.03) there.
    """
    if zone == ZONE_OCEAN:
        cont_lo, cont_hi = OCEAN_CONTINENTALNESS_LO, OCEAN_CONTINENTALNESS_HI
    else:
        cont_lo, cont_hi = LAND_CONTINENTALNESS_LO, LAND_CONTINENTALNESS_HI

    c = biome_c or {}
    return {
        'temperature':     [temp,                                        temp],
        'humidity':        [hum,                                         hum],
        'continentalness': [cont_lo,                                     cont_hi],
        'erosion':         [c.get('erosion_lo',  EROSION_MIN),           c.get('erosion_hi',  EROSION_MAX)],
        'depth':           [c.get('depth_lo',    SURFACE_DEPTH),         c.get('depth_hi',    SURFACE_DEPTH)],
        'weirdness':       [c.get('weirdness_lo', WEIRDNESS_MIN),        c.get('weirdness_hi', WEIRDNESS_MAX)],
    }


# ── Image helpers ─────────────────────────────────────────────────────────────

def _encode_image_as_png_data_url(rgb_uint8: np.ndarray) -> str:
    """Encodes at native resolution (no upscale); canvas CSS handles display size."""
    png_buffer = io.BytesIO()
    plt.imsave(png_buffer, rgb_uint8, format='png')
    png_buffer.seek(0)
    return 'data:image/png;base64,' + base64.b64encode(png_buffer.read()).decode()


def _biome_to_hex(biome_id: str) -> str:
    r, g, b = [int(channel * 255) for channel in biome_color_rgb(biome_id)]
    return f'#{r:02x}{g:02x}{b:02x}'


# ── Brush selector ────────────────────────────────────────────────────────────

def _render_paint_brush_selector(planner_biomes: list[str]) -> str:
    options = [PAINT_ERASE_BRUSH] + sorted(planner_biomes)
    labels  = ['✕ Erase'] + sorted(planner_biomes)

    stored   = st.session_state.get('paint_brush', PAINT_ERASE_BRUSH)
    init_idx = options.index(stored) if stored in options else 0

    col_label, col_select = st.columns([1, 5])
    col_label.markdown('**Brush**')
    chosen_label = col_select.selectbox(
        'brush', labels, index=init_idx,
        key='paint_brush_select', label_visibility='collapsed',
    )
    chosen = options[labels.index(chosen_label)]
    st.session_state['paint_brush'] = chosen

    if chosen != PAINT_ERASE_BRUSH:
        r, g, b = [int(channel * 255) for channel in biome_color_rgb(chosen)]
        st.markdown(
            f'<span style="display:inline-block;width:12px;height:12px;'
            f'background:rgb({r},{g},{b});border-radius:2px;margin-right:6px;"></span>'
            f'{chosen}',
            unsafe_allow_html=True,
        )
    return chosen


# ── Click processing ──────────────────────────────────────────────────────────

def _process_paint_click(
    click:        dict | None,
    assignments:  dict[str, str],
    active_brush: str,
) -> bool:
    """Applies a click event to assignments. Returns True if anything changed."""
    if not click or not isinstance(click, dict):
        return False

    click_ts = click.get('ts')
    if click_ts == st.session_state.get('paint_last_ts'):
        return False
    st.session_state['paint_last_ts'] = click_ts

    key = f'{int(click["row"])},{int(click["col"])}'
    if active_brush == PAINT_ERASE_BRUSH:
        changed = key in assignments
        assignments.pop(key, None)
    else:
        changed = assignments.get(key) != active_brush
        assignments[key] = active_brush
    return changed


# ── Main entry point ──────────────────────────────────────────────────────────

def render_world_paint_tab(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
    planner_biomes:     list[str],
) -> None:
    """Renders the HTML canvas paint editor. Paint state loaded by main() before this."""
    active_brush = _render_paint_brush_selector(planner_biomes)
    st.caption('Click the map to assign the active biome. Select ✕ Erase to clear.')

    assignments = st.session_state['paint_assignments']

    image, _  = build_rgb_image(nearest_entry_grid, entries)
    rgb_uint8 = (image * 255).astype(np.uint8)

    click = _paint_component(
        image_data_url=_encode_image_as_png_data_url(rgb_uint8),
        paint_colors={key: _biome_to_hex(biome_id) for key, biome_id in assignments.items()},
        step=PAINT_DOWNSAMPLE_STEP,
        key='paint_canvas',
        height=PAINT_CHART_HEIGHT_PX,
    )

    if _process_paint_click(click, assignments, active_brush):
        st.session_state['paint_seeds'] = None
        save_paint_assignments(assignments)
        delete_paint_seeds()
        st.rerun()

    painted_count = len(assignments)
    st.caption(f'{painted_count} cell(s) painted.')

    if painted_count and st.button('Clear all paint'):
        st.session_state['paint_assignments'] = {}
        st.session_state['paint_seeds']       = None
        save_paint_assignments({})
        delete_paint_seeds()
        st.rerun()
