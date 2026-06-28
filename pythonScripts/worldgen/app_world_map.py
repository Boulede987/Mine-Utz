"""
worldgen/app_world_map.py
==========================
World Map tab: live biome preview, click-to-highlight, PNG/Java export.
"""

import base64
import io

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
import plotly.graph_objects as go
import streamlit as st

from worldgen.app_data import EXPORT_RESOLUTION, MAP_SEED
from worldgen.biome_colour import build_highlighted_image, build_rgb_image
from worldgen.biome_grid import generate_grid_entries
from worldgen.biome_nearest_neighbour import build_parameter_bounds, compute_nearest_entry_grid
from worldgen.biome_noise import climate_maps_to_query_grid, generate_all_climate_maps
from worldgen.constants import FILTER_REGION_JAVA
from worldgen.terrablender_codegen import build_region

CLICK_GRID_DOWNSAMPLE_STEP  = 4
CLICK_MARKER_SIZE           = 16
NO_HIGHLIGHT_LABEL          = '— none —'
LAYOUT_IMAGE_UPSCALE_FACTOR = 4
EXPORT_FIGURE_SIZE_INCHES   = 10
EXPORT_PNG_DPI              = 150
MAP_CHART_HEIGHT_PX         = 520


@st.cache_data(show_spinner='Generating export-resolution climate maps...')
def load_export_query_grid() -> np.ndarray:
    return climate_maps_to_query_grid(
        generate_all_climate_maps(EXPORT_RESOLUTION, EXPORT_RESOLUTION, MAP_SEED)
    )


def _grid_entries_to_region_params(entries: list[dict]) -> dict[str, list[dict]]:
    region_params: dict[str, list[dict]] = {}
    for entry in entries:
        param = {**entry['parameters'], 'offset': 0.0}
        region_params.setdefault(entry['biome'], []).append(param)
    return region_params


def _build_click_scatter(nearest_entry_grid: np.ndarray, entries: list[dict]) -> go.Scatter:
    """Downsampled scatter grid for click and hover detection.

    The image is placed as a non-interactive layout.images background, so this scatter
    is the only interactive layer. y-coordinates are flipped to match the standard
    Plotly axis convention (y=0 at bottom) while the image origin is at (x=0, y=height).
    Markers are near-invisible but present so Plotly registers click events.
    """
    height, width = nearest_entry_grid.shape
    entry_biomes  = [entry['biome'] for entry in entries]
    half_step     = CLICK_GRID_DOWNSAMPLE_STEP // 2
    sample_rows   = range(half_step, height, CLICK_GRID_DOWNSAMPLE_STEP)
    sample_cols   = range(half_step, width,  CLICK_GRID_DOWNSAMPLE_STEP)

    xs, ys, biome_names = [], [], []
    for row in sample_rows:
        for col in sample_cols:
            xs.append(col + 0.5)
            ys.append(height - row - 0.5)
            biome_names.append(entry_biomes[nearest_entry_grid[row, col]])

    return go.Scatter(
        x=xs, y=ys,
        mode='markers',
        marker=dict(symbol='square', size=CLICK_MARKER_SIZE, opacity=0.01, color='rgba(0,0,0,0)'),
        customdata=biome_names,
        hovertemplate='%{customdata}<extra></extra>',
        showlegend=False,
    )


def _extract_biome_from_click(event_data) -> str | None:
    if not event_data or not event_data.selection or not event_data.selection.points:
        return None
    custom = event_data.selection.points[0].get('customdata')
    return custom if isinstance(custom, str) else None


def _encode_image_as_png_data_url(rgb_uint8: np.ndarray) -> str:
    upscaled   = rgb_uint8.repeat(LAYOUT_IMAGE_UPSCALE_FACTOR, axis=0).repeat(LAYOUT_IMAGE_UPSCALE_FACTOR, axis=1)
    png_buffer = io.BytesIO()
    plt.imsave(png_buffer, upscaled, format='png')
    png_buffer.seek(0)
    return 'data:image/png;base64,' + base64.b64encode(png_buffer.read()).decode()


def render_preview_map_plotly(
    nearest_entry_grid:   np.ndarray,
    entries:              list[dict],
    highlighted_biome_id: str | None,
    biome_colors:         dict[str, tuple],
):
    """Renders biome map with click-to-highlight and hover.

    The image is placed as a non-interactive layout.images background so it does not
    absorb mouse events. An invisible scatter grid on top handles clicks and hover.
    """
    if highlighted_biome_id:
        image = build_highlighted_image(nearest_entry_grid, entries, highlighted_biome_id, biome_colors)
    else:
        image, _ = build_rgb_image(nearest_entry_grid, entries)

    rgb_uint8     = (image * 255).astype(np.uint8)
    height, width = rgb_uint8.shape[:2]

    figure = go.Figure(_build_click_scatter(nearest_entry_grid, entries))
    figure.add_layout_image(dict(
        source   = _encode_image_as_png_data_url(rgb_uint8),
        xref='x', yref='y',
        x=0,     y=height,
        sizex=width, sizey=height,
        sizing='stretch',
        opacity=1,
        layer='below',
    ))
    figure.update_layout(
        margin=dict(l=0, r=0, t=0, b=0),
        height=MAP_CHART_HEIGHT_PX,
        xaxis=dict(range=[0, width],  visible=False, showgrid=False),
        yaxis=dict(range=[0, height], visible=False, showgrid=False, scaleanchor='x', scaleratio=1),
        clickmode='event+select',
        dragmode='pan',
        plot_bgcolor='rgba(0,0,0,0)',
        uirevision='world_map',
    )
    return st.plotly_chart(figure, use_container_width=True, on_select='rerun', key='world_map_chart')


def _render_map_to_png_bytes(nearest_entry_grid: np.ndarray, entries: list[dict]) -> bytes:
    image, _     = build_rgb_image(nearest_entry_grid, entries)
    figure, axis = plt.subplots(figsize=(EXPORT_FIGURE_SIZE_INCHES, EXPORT_FIGURE_SIZE_INCHES))
    axis.imshow(image, origin='upper', interpolation='nearest', aspect='equal')
    axis.axis('off')
    figure.tight_layout(pad=0)
    buffer = io.BytesIO()
    figure.savefig(buffer, format='png', dpi=EXPORT_PNG_DPI, bbox_inches='tight')
    plt.close(figure)
    buffer.seek(0)
    return buffer.read()


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
                region_params = _grid_entries_to_region_params(entries)
                biome_ids     = sorted(region_params.keys())
                total_entries = sum(len(params) for params in region_params.values())
                build_region(biome_ids, region_params, 'FilterRegion', 'OVERWORLD', FILTER_REGION_JAVA)
                st.session_state['java_export_ok']   = (len(biome_ids), total_entries)
                st.session_state.pop('java_export_error', None)

    with col_java_status:
        if 'java_export_ok' in st.session_state:
            biome_count, entry_count = st.session_state['java_export_ok']
            st.success(f'{biome_count} biomes, {entry_count} entries → FilterRegion.java. Rebuild mod in IntelliJ.')
        elif 'java_export_error' in st.session_state:
            st.error(f'Companion mod not found: {st.session_state["java_export_error"]}')


def render_world_map_tab(
    nearest_entry_grid: np.ndarray,
    entries:            list[dict],
    data:               dict,
    biome_weights:      dict[str, float],
    constraints:        dict[str, dict],
) -> None:
    from worldgen.app_auto_layout import render_auto_layout_section
    _, biome_colors   = build_rgb_image(nearest_entry_grid, entries)
    entry_biomes      = [entry['biome'] for entry in entries]
    visible_biome_ids = sorted({entry_biomes[idx] for idx in np.unique(nearest_entry_grid)})

    if 'map_pending_highlight' in st.session_state:
        st.session_state['highlight_biome'] = st.session_state.pop('map_pending_highlight')

    highlight_label      = st.selectbox(
        'Highlight biome (click map or select here)',
        options=[NO_HIGHLIGHT_LABEL] + visible_biome_ids,
        key='highlight_biome',
    )
    highlighted_biome_id = None if highlight_label == NO_HIGHLIGHT_LABEL else highlight_label

    event_data    = render_preview_map_plotly(nearest_entry_grid, entries, highlighted_biome_id, biome_colors)
    clicked_biome = _extract_biome_from_click(event_data)
    if clicked_biome:
        new_label = NO_HIGHLIGHT_LABEL if clicked_biome == highlighted_biome_id else clicked_biome
        st.session_state['map_pending_highlight'] = new_label
        st.rerun()

    render_export_section(data, biome_weights, constraints, entries)
    render_auto_layout_section(data['planner_biomes'], biome_weights, constraints, data['preview_query'])
