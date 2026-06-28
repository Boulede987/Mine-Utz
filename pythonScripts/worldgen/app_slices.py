"""
worldgen/app_slices.py
=======================
Parameter Slices tab: interactive 2D cross-sections through the 6D climate space.
"""

import numpy as np
import plotly.graph_objects as go
import streamlit as st

from worldgen.app_data import AXIS_VALUE_RANGES, SLICE_RESOLUTION
from worldgen.biome_colour import build_rgb_image
from worldgen.biome_nearest_neighbour import compute_nearest_entry_grid
from worldgen.constants import PARAMETER_AXES

SLICE_TICK_COUNT      = 5
SLICE_CHART_HEIGHT_PX = 360
SLICE_FIXED_AXIS_STEP = 0.05


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
        nearest_entry_grid, entries,
        slice_config['x_axis'], slice_config['y_axis'],
        x_lo, x_hi, y_lo, y_hi,
    )

    tick_pixel = np.linspace(0, SLICE_RESOLUTION - 1, SLICE_TICK_COUNT)
    figure = go.Figure(go.Image(
        z=rgb_uint8,
        customdata=hover_grid,
        hovertemplate='%{customdata}<extra></extra>',
    ))
    figure.update_layout(
        title=dict(text=slice_config['title'], font=dict(size=11)),
        margin=dict(l=50, r=10, t=40, b=50),
        height=SLICE_CHART_HEIGHT_PX,
        xaxis=dict(
            title=slice_config['x_axis'],
            tickvals=tick_pixel,
            ticktext=[f'{v:.2f}' for v in np.linspace(x_lo, x_hi, SLICE_TICK_COUNT)],
        ),
        yaxis=dict(
            title=slice_config['y_axis'],
            tickvals=tick_pixel,
            ticktext=[f'{v:.2f}' for v in np.linspace(y_hi, y_lo, SLICE_TICK_COUNT)],
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
    fixed_axes    = [axis for axis in PARAMETER_AXES if axis not in (x_axis, y_axis)]
    fixed_values: dict[str, float] = {}
    columns       = st.columns(len(fixed_axes))
    for column, axis in zip(columns, fixed_axes):
        axis_lo, axis_hi   = AXIS_VALUE_RANGES[axis]
        axis_midpoint      = round((axis_lo + axis_hi) / 2.0, 2)
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
