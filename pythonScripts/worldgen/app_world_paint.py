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
from worldgen.constants import PAINT_ASSIGNMENTS_FILE, PARAMETER_AXES

PAINT_ERASE_BRUSH           = '__erase__'
PAINT_DOWNSAMPLE_STEP       = 4
PAINT_CHART_HEIGHT_PX       = 524
MAX_PAINT_SAMPLES_PER_BIOME = 256

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


# ── Entry generation ──────────────────────────────────────────────────────────

def paint_to_entries(
    assignments:   dict[str, str],
    preview_query: np.ndarray,
) -> list[dict]:
    """Converts painted cell assignments to TerraBlender point-anchor entries.

    Each sampled cell becomes one entry with lo=hi=climate_value on all 6 axes.
    Callers should place these before weight-based entries so they win
    nearest-neighbour ties at painted locations.
    """
    height, width = preview_query.shape[:2]
    biome_pixels: dict[str, list[tuple[int, int]]] = {}

    for key, biome_id in assignments.items():
        row, col = map(int, key.split(','))
        if 0 <= row < height and 0 <= col < width:
            biome_pixels.setdefault(biome_id, []).append((row, col))

    entries = []
    for biome_id, pixels in biome_pixels.items():
        for row, col in _even_sample(pixels, MAX_PAINT_SAMPLES_PER_BIOME):
            climate = preview_query[row, col]
            entries.append({
                'biome': biome_id,
                'parameters': {
                    axis: [float(climate[axis_idx]), float(climate[axis_idx])]
                    for axis_idx, axis in enumerate(PARAMETER_AXES)
                },
            })
    return entries


def _even_sample(items: list, max_count: int) -> list:
    if len(items) <= max_count:
        return items
    step = len(items) / max_count
    return [items[int(index * step)] for index in range(max_count)]


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
        save_paint_assignments(assignments)
        st.rerun()

    painted_count = len(assignments)
    st.caption(f'{painted_count} cell(s) painted.')

    if painted_count and st.button('Clear all paint'):
        st.session_state['paint_assignments'] = {}
        save_paint_assignments({})
        st.rerun()
