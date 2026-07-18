"""
worldgen/entry_pipeline.py
===========================
Non-Streamlit reproduction of the Overworld entry computation in app.py's main().

generate_filter_region.py previously built FilterRegion.java straight from the raw,
unmodified climate positions scanned out of the originating mod jars (worldgen_data/
merged/*.json) — every weight/zone/constraint tweak, paint-map edit, and K-means
auto-layout run in the Streamlit app was pure preview, never reaching the generated
Java. compute_overworld_entries() is the same paint_seeds > paint_assignments >
weight-grid priority app.py uses, so codegen and the app can no longer disagree
about what actually gets placed.
"""

from worldgen.app_data import MAP_SEED, PREVIEW_RESOLUTION
from worldgen.app_world_paint import (
    climate_seeds_to_entries, load_paint_assignments, load_paint_seeds,
    paint_covers_full_map, paint_to_entries,
)
from worldgen.biome_constraints import load_settings, resolve_all_constraints
from worldgen.biome_grid import generate_grid_entries
from worldgen.biome_loader import load_biome_parameter_defaults, load_planner_overworld_biomes
from worldgen.biome_noise import climate_maps_to_query_grid, generate_all_climate_maps

ENTRY_OFFSET = 0.0   # Climate.ParameterPoint fitness offset; app-generated entries don't use it.


def compute_overworld_entries() -> list[dict]:
    """Returns the same [{'biome': id, 'parameters': {...}}] entries app.py renders.

    Empty list means no tuned data exists yet (run the Streamlit app first) —
    the caller should fall back to raw mod climate positions.
    """
    planner_biomes = load_planner_overworld_biomes()
    if not planner_biomes:
        return []

    saved_settings = load_settings()
    biome_defaults = load_biome_parameter_defaults(planner_biomes)
    biome_weights, constraints = resolve_all_constraints(planner_biomes, saved_settings, biome_defaults)

    preview_query = climate_maps_to_query_grid(
        generate_all_climate_maps(PREVIEW_RESOLUTION, PREVIEW_RESOLUTION, MAP_SEED)
    )

    paint_seeds       = load_paint_seeds()
    paint_assignments = load_paint_assignments()

    if paint_seeds is not None:
        paint_entries = climate_seeds_to_entries(paint_seeds, constraints)
    elif paint_assignments:
        paint_entries = paint_to_entries(paint_assignments, preview_query, constraints)
    else:
        paint_entries = []

    height, width = preview_query.shape[:2]
    if paint_covers_full_map(paint_assignments, height, width):
        return paint_entries

    weight_entries = generate_grid_entries(planner_biomes, biome_weights, constraints)
    return paint_entries + weight_entries


def entries_to_all_params(entries: list[dict]) -> dict[str, list[dict]]:
    """Converts app-style entries to the {biome_id: [param_dict_with_offset, ...]}
    shape terrablender_codegen.build_region expects."""
    all_params: dict[str, list[dict]] = {}
    for entry in entries:
        params = dict(entry['parameters'])
        params['offset'] = ENTRY_OFFSET
        all_params.setdefault(entry['biome'], []).append(params)
    return all_params
