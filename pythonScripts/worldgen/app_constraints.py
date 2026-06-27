"""
worldgen/app_constraints.py
=============================
Streamlit UI for the Constraints tab.

Each biome gets a zone selector (land / ocean) and optional temperature/humidity
range inputs.  Values are stored in session state via widget keys and written
back to the constraints dict on every rerun.
"""

import streamlit as st

from worldgen.app_data import group_biome_ids_by_namespace
from worldgen.biome_grid import (
    HUMIDITY_MAX, HUMIDITY_MIN, TEMPERATURE_MAX, TEMPERATURE_MIN,
    ZONE_LAND, ZONE_OCEAN, default_zone,
)


def render_constraints_tab(
    planner_biomes: list[str],
    constraints:    dict[str, dict],
) -> dict[str, dict]:
    """Renders per-biome constraint editors. Returns the full updated constraints dict."""
    st.caption(
        'Zone determines the continentalness range (land vs ocean). '
        'Temperature and humidity ranges restrict which grid cells a biome can occupy. '
        'Defaults: zone inferred from biome name, full temperature and humidity range.'
    )

    updated  = dict(constraints)
    grouped  = group_biome_ids_by_namespace(planner_biomes)

    for namespace, biome_paths in grouped.items():
        with st.expander(f'{namespace}  ({len(biome_paths)} biomes)', expanded=False):
            _render_column_headers()
            for path in biome_paths:
                biome_id = f'{namespace}:{path}'
                updated[biome_id] = _render_biome_constraint_row(
                    path, biome_id, constraints.get(biome_id, {})
                )

    return updated


def _render_column_headers() -> None:
    columns = st.columns([2, 1, 1, 1, 1, 1])
    columns[0].markdown('**Biome**')
    columns[1].markdown('**Zone**')
    columns[2].markdown('**Temp min**')
    columns[3].markdown('**Temp max**')
    columns[4].markdown('**Hum min**')
    columns[5].markdown('**Hum max**')


def _render_biome_constraint_row(
    path:       str,
    biome_id:   str,
    constraint: dict,
) -> dict:
    """Renders one biome row and returns its current constraint dict."""
    columns     = st.columns([2, 1, 1, 1, 1, 1])
    zone_index  = 0 if constraint.get('zone', default_zone(biome_id)) == ZONE_LAND else 1

    columns[0].write(path)
    zone    = columns[1].selectbox(
        'zone', [ZONE_LAND, ZONE_OCEAN], index=zone_index,
        key=f'czone_{biome_id}', label_visibility='collapsed',
    )
    temp_lo = columns[2].number_input(
        'temp_lo',
        min_value=float(TEMPERATURE_MIN), max_value=float(TEMPERATURE_MAX),
        value=float(constraint.get('temperature_lo', TEMPERATURE_MIN)),
        step=0.1, format='%.1f',
        key=f'ctlo_{biome_id}', label_visibility='collapsed',
    )
    temp_hi = columns[3].number_input(
        'temp_hi',
        min_value=float(TEMPERATURE_MIN), max_value=float(TEMPERATURE_MAX),
        value=float(constraint.get('temperature_hi', TEMPERATURE_MAX)),
        step=0.1, format='%.1f',
        key=f'cthi_{biome_id}', label_visibility='collapsed',
    )
    hum_lo  = columns[4].number_input(
        'hum_lo',
        min_value=float(HUMIDITY_MIN), max_value=float(HUMIDITY_MAX),
        value=float(constraint.get('humidity_lo', HUMIDITY_MIN)),
        step=0.1, format='%.1f',
        key=f'chlo_{biome_id}', label_visibility='collapsed',
    )
    hum_hi  = columns[5].number_input(
        'hum_hi',
        min_value=float(HUMIDITY_MIN), max_value=float(HUMIDITY_MAX),
        value=float(constraint.get('humidity_hi', HUMIDITY_MAX)),
        step=0.1, format='%.1f',
        key=f'chhi_{biome_id}', label_visibility='collapsed',
    )

    return {
        'zone':           zone,
        'temperature_lo': temp_lo,
        'temperature_hi': temp_hi,
        'humidity_lo':    hum_lo,
        'humidity_hi':    hum_hi,
    }
