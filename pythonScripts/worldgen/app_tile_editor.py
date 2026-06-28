"""
worldgen/app_tile_editor.py
============================
Click-to-paint tile map editor for biome climate assignments.

Each cell is a colored square scatter marker. Scatter points participate in
Plotly's selection system, so on_select fires reliably on single click.
Heatmap trace is intentionally avoided: heatmap cells are not selectable points.
"""

from typing import Callable

import numpy as np
import plotly.graph_objects as go
import streamlit as st

from worldgen.biome_colour import biome_color_rgb
from worldgen.biome_grid import (
    HUMIDITY_MAX, HUMIDITY_MIN,
    LAND_HUMIDITY_BANDS, LAND_TEMPERATURE_BANDS,
    OCEAN_TEMPERATURE_BANDS,
    TEMPERATURE_MAX, TEMPERATURE_MIN,
)
from worldgen.tile_assignments import (
    load_tile_assignments, save_tile_assignments, tile_editor_to_entries,
)

ERASE_BRUSH            = '__erase__'
UNASSIGNED_LABEL       = '— unassigned —'
UNASSIGNED_COLOR       = 'rgb(40,40,40)'
LAND_GRID_HEIGHT       = 420
OCEAN_GRID_HEIGHT      = 140
CHART_VERTICAL_MARGINS = 60  # top=10 + bottom=50

type CellSpec = tuple[int, int, str]  # (temp_idx, hum_idx, stored_key)


# ── Key builders ──────────────────────────────────────────────────────────────

def _land_cell_key(temp_idx: int, hum_idx: int) -> str:
    return f'{temp_idx},{hum_idx}'


def _ocean_cell_key(temp_idx: int, hum_idx: int) -> str:
    return str(temp_idx)


def _land_cell_specs(temperature_band_count: int, humidity_band_count: int) -> list[CellSpec]:
    return [
        (temp_idx, hum_idx, _land_cell_key(temp_idx, hum_idx))
        for hum_idx in range(humidity_band_count)
        for temp_idx in range(temperature_band_count)
    ]


def _ocean_cell_specs(temperature_band_count: int) -> list[CellSpec]:
    return [(temp_idx, 0, _ocean_cell_key(temp_idx, 0)) for temp_idx in range(temperature_band_count)]


# ── Helpers ───────────────────────────────────────────────────────────────────

def _rgb_str(biome_id: str) -> str:
    r, g, b = [int(channel * 255) for channel in biome_color_rgb(biome_id)]
    return f'rgb({r},{g},{b})'


def _band_centers(lo: float, hi: float, band_count: int) -> list[float]:
    edges = np.linspace(lo, hi, band_count + 1)
    return [(float(edges[index]) + float(edges[index + 1])) / 2.0 for index in range(band_count)]


def _short_label(biome_id: str | None) -> str:
    if not biome_id:
        return ''
    path = biome_id.split(':', 1)[1]
    return path[:12] if len(path) > 12 else path


# ── Cell data builders ────────────────────────────────────────────────────────

def _cell_colors(assignments: dict[str, str], cell_specs: list[CellSpec]) -> list[str]:
    return [
        _rgb_str(assignments[key]) if key in assignments else UNASSIGNED_COLOR
        for _, _, key in cell_specs
    ]


def _cell_hover_texts(
    assignments:         dict[str, str],
    cell_specs:          list[CellSpec],
    temp_centers:        list[float],
    hum_centers:         list[float],
    humidity_band_count: int,
) -> list[str]:
    texts = []
    for temp_idx, hum_idx, key in cell_specs:
        biome_id = assignments.get(key)
        label    = biome_id if biome_id else UNASSIGNED_LABEL
        tooltip  = f'{label}<br>temp≈{temp_centers[temp_idx]:.2f}'
        if humidity_band_count > 1:
            tooltip += f'  hum≈{hum_centers[hum_idx]:.2f}'
        texts.append(tooltip)
    return texts


def _cell_short_labels(assignments: dict[str, str], cell_specs: list[CellSpec]) -> list[str]:
    return [_short_label(assignments.get(key)) for _, _, key in cell_specs]


# ── Figure builder ────────────────────────────────────────────────────────────

def _build_grid_figure(
    assignments:            dict[str, str],
    temperature_band_count: int,
    humidity_band_count:    int,
    temp_centers:           list[float],
    hum_centers:            list[float],
    height:                 int,
    cell_specs:             list[CellSpec],
    y_axis_title:           str,
    chart_key:              str,
) -> go.Figure:
    plot_height  = height - CHART_VERTICAL_MARGINS
    marker_size  = max(10, int(plot_height / humidity_band_count * 0.85))

    cell_x       = [temp_idx for temp_idx, _, _ in cell_specs]
    cell_y       = [hum_idx  for _, hum_idx, _ in cell_specs]
    colors       = _cell_colors(assignments, cell_specs)
    hover_texts  = _cell_hover_texts(assignments, cell_specs, temp_centers, hum_centers, humidity_band_count)
    short_labels = _cell_short_labels(assignments, cell_specs)

    cell_scatter = go.Scatter(
        x=cell_x,
        y=cell_y,
        mode='markers+text',
        marker=dict(
            symbol='square',
            size=marker_size,
            color=colors,
            line=dict(width=1, color='rgba(255,255,255,0.2)'),
        ),
        text=short_labels,
        textfont=dict(size=7, color='white'),
        customdata=hover_texts,
        hovertemplate='%{customdata}<extra></extra>',
        showlegend=False,
    )

    figure = go.Figure([cell_scatter])
    figure.update_layout(
        height=height,
        margin=dict(l=50, r=10, t=10, b=50),
        xaxis=dict(
            title='Temperature',
            tickvals=list(range(temperature_band_count)),
            ticktext=[f'{center:.2f}' for center in temp_centers],
            range=[-0.5, temperature_band_count - 0.5],
            showgrid=False,
        ),
        yaxis=dict(
            title=y_axis_title,
            tickvals=list(range(humidity_band_count)),
            ticktext=[f'{center:.2f}' for center in hum_centers],
            range=[-0.5, humidity_band_count - 0.5],
            showgrid=False,
        ),
        plot_bgcolor='rgb(20,20,20)',
        clickmode='event+select',
        dragmode='pan',
        uirevision=chart_key,
    )
    return figure


# ── Click handling ────────────────────────────────────────────────────────────

def _apply_click(
    event_data:   object,
    zone:         str,
    assignments:  dict[str, str],
    active_brush: str,
    key_fn:       Callable[[int, int], str],
) -> bool:
    """Reads clicked cell from event_data and updates assignments. Returns True if changed."""
    if not event_data or not event_data.selection.points:
        return False

    consumed   = f'tile_consumed_{zone}'
    last_stamp = st.session_state.get(consumed)

    # Selections accumulate with clickmode='event+select', so scan all points
    # for one that hasn't been processed yet.
    for point in event_data.selection.points:
        temp_idx    = int(point['x'])
        hum_idx     = int(point.get('y', 0))
        key         = key_fn(temp_idx, hum_idx)
        click_stamp = (key, active_brush)
        if click_stamp == last_stamp:
            continue

        st.session_state[consumed] = click_stamp

        if active_brush == ERASE_BRUSH:
            changed = key in assignments
            assignments.pop(key, None)
        else:
            changed = assignments.get(key) != active_brush
            assignments[key] = active_brush

        return changed

    return False


# ── Brush selector ────────────────────────────────────────────────────────────

def _render_brush_selector(planner_biomes: list[str]) -> str:
    """Renders the biome brush picker. Returns the active brush biome_id or ERASE_BRUSH."""
    options = [ERASE_BRUSH] + sorted(planner_biomes)
    labels  = ['✕ Erase'] + sorted(planner_biomes)

    stored   = st.session_state.get('tile_brush', ERASE_BRUSH)
    init_idx = options.index(stored) if stored in options else 0

    col_label, col_select = st.columns([1, 5])
    col_label.markdown('**Brush**')
    chosen_label = col_select.selectbox(
        'brush', labels, index=init_idx,
        key='tile_brush_select', label_visibility='collapsed',
    )
    chosen = options[labels.index(chosen_label)]
    st.session_state['tile_brush'] = chosen

    if chosen != ERASE_BRUSH:
        r, g, b = [int(channel * 255) for channel in biome_color_rgb(chosen)]
        st.markdown(
            f'<span style="display:inline-block;width:12px;height:12px;'
            f'background:rgb({r},{g},{b});border-radius:2px;margin-right:6px;"></span>'
            f'{chosen}',
            unsafe_allow_html=True,
        )
    return chosen


# ── Zone grid ─────────────────────────────────────────────────────────────────

def _render_zone_grid(
    zone_label:             str,
    assignments:            dict[str, str],
    temperature_band_count: int,
    humidity_band_count:    int,
    temp_centers:           list[float],
    hum_centers:            list[float],
    active_brush:           str,
    chart_key:              str,
    cell_specs:             list[CellSpec],
    key_fn:                 Callable[[int, int], str],
    y_axis_title:           str,
    height:                 int,
) -> bool:
    """Renders one zone grid. Returns True if any assignment changed."""
    st.markdown(f'**{zone_label}**')
    figure     = _build_grid_figure(
        assignments, temperature_band_count, humidity_band_count,
        temp_centers, hum_centers, height, cell_specs, y_axis_title, chart_key,
    )
    event_data = st.plotly_chart(figure, on_select='rerun', key=chart_key, use_container_width=True)
    return _apply_click(event_data, zone_label, assignments, active_brush, key_fn)


# ── Main entry point ──────────────────────────────────────────────────────────

def render_tile_map_tab(planner_biomes: list[str]) -> list[dict]:
    """Renders the tile map editor. Returns the entry list for preview/export."""
    if 'tile_loaded' not in st.session_state:
        saved = load_tile_assignments()
        st.session_state['tile_land']   = saved.get('land',  {})
        st.session_state['tile_ocean']  = saved.get('ocean', {})
        st.session_state['tile_loaded'] = True

    land_temp_centers  = _band_centers(TEMPERATURE_MIN, TEMPERATURE_MAX, LAND_TEMPERATURE_BANDS)
    land_hum_centers   = _band_centers(HUMIDITY_MIN,    HUMIDITY_MAX,    LAND_HUMIDITY_BANDS)
    ocean_temp_centers = _band_centers(TEMPERATURE_MIN, TEMPERATURE_MAX, OCEAN_TEMPERATURE_BANDS)
    land_specs         = _land_cell_specs(LAND_TEMPERATURE_BANDS, LAND_HUMIDITY_BANDS)
    ocean_specs        = _ocean_cell_specs(OCEAN_TEMPERATURE_BANDS)

    active_brush = _render_brush_selector(planner_biomes)
    st.caption('Click a cell to assign the active biome. Select ✕ Erase to clear a cell.')

    land_assignments  = st.session_state['tile_land']
    ocean_assignments = st.session_state['tile_ocean']

    land_changed = _render_zone_grid(
        'Land', land_assignments,
        LAND_TEMPERATURE_BANDS, LAND_HUMIDITY_BANDS,
        land_temp_centers, land_hum_centers,
        active_brush, 'tile_land_chart',
        land_specs, _land_cell_key, 'Humidity',
        LAND_GRID_HEIGHT,
    )
    ocean_changed = _render_zone_grid(
        'Ocean', ocean_assignments,
        OCEAN_TEMPERATURE_BANDS, 1,
        ocean_temp_centers, [0.0],
        active_brush, 'tile_ocean_chart',
        ocean_specs, _ocean_cell_key, '',
        OCEAN_GRID_HEIGHT,
    )

    if land_changed or ocean_changed:
        save_tile_assignments({'land': land_assignments, 'ocean': ocean_assignments})
        st.rerun()

    assigned = len(land_assignments) + len(ocean_assignments)
    total    = LAND_TEMPERATURE_BANDS * LAND_HUMIDITY_BANDS + OCEAN_TEMPERATURE_BANDS
    st.caption(f'{assigned} / {total} cells assigned.')

    return tile_editor_to_entries(land_assignments, ocean_assignments)
