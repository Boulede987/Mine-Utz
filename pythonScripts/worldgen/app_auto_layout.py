"""
worldgen/app_auto_layout.py
============================
K-means-based automatic biome layout generator.

Clusters land/ocean cells in temperature×humidity×erosion climate space, then
assigns biomes to clusters proportional to weight and temperature preference.
Each cluster centre becomes one TerraBlender point anchor.  K-means guarantees
every cluster is a contiguous Voronoi region in climate space — no interleaving.

Non-surface biomes (depth_hi > 0) skip K-means — the surface preview has no
depth variation — and receive a single midpoint anchor from their constraint ranges.

Generated climate seeds overwrite paint_assignments — all tabs update on rerun.
"""

import numpy as np
import streamlit as st

from worldgen.app_world_paint import PAINT_DOWNSAMPLE_STEP, save_paint_assignments, save_paint_seeds
from worldgen.biome_grid import (
    HUMIDITY_MAX, HUMIDITY_MIN,
    SURFACE_DEPTH,
    TEMPERATURE_MAX, TEMPERATURE_MIN,
    ZONE_LAND, ZONE_OCEAN,
    default_zone,
)
from worldgen.constants import PARAMETER_AXES

CONTINENT_AXIS_IDX   = PARAMETER_AXES.index('continentalness')
TEMPERATURE_AXIS_IDX = PARAMETER_AXES.index('temperature')
HUMIDITY_AXIS_IDX    = PARAMETER_AXES.index('humidity')
EROSION_AXIS_IDX     = PARAMETER_AXES.index('erosion')

# K-means land/ocean split threshold — vanilla coast zone start.
# Intentionally -0.19, not LAND_CONTINENTALNESS_LO (0.03).
# Coast + near-inland cells (cont -0.19→0.03) go to the land K-means pool so
# cluster centers are computed from the full land area.
# The TerraBlender anchor for land still uses LAND_CONTINENTALNESS_LO (0.03)
# to maintain a strong zone penalty in deep ocean.
LAND_CONTINENT_THRESHOLD = -0.19

DEFAULT_CELLS_PER_PATCH = 16
MIN_CELLS_PER_PATCH     = 2
MAX_CELLS_PER_PATCH     = 128
KMEANS_MAX_ITERS        = 100


# ── Core algorithm ────────────────────────────────────────────────────────────

def auto_distribute_biomes(
    planner_biomes:  list[str],
    weights:         dict[str, float],
    constraints:     dict[str, dict],
    preview_query:   np.ndarray,
    cells_per_patch: int,
    rng_seed:        int,
) -> tuple[dict[str, str], list[list]]:
    """Returns (assignments, climate_seeds).

    assignments:   {'{row},{col}': biome_id} covering all surface land and ocean cells.
    climate_seeds: [[temp, hum, biome_id], ...] — one entry per K-means cluster plus
                   one entry per non-surface biome (midpoint anchor).

    K-means in (temperature, humidity, erosion) space ensures each cluster is a
    contiguous Voronoi region → biomes never interleave.  Erosion as a third axis
    lets mountain biomes cluster away from valley biomes naturally.

    Non-surface biomes (depth_hi > 0) are excluded from the surface K-means pool
    and receive a single midpoint anchor derived from their constraint ranges.
    """
    step          = PAINT_DOWNSAMPLE_STEP
    half          = step // 2
    height, width = preview_query.shape[:2]

    all_cells   = _build_cell_grid(height, width, step, half)
    cont_values = preview_query[all_cells[:, 0], all_cells[:, 1], CONTINENT_AXIS_IDX]

    land_mask  = cont_values >= LAND_CONTINENT_THRESHOLD
    ocean_mask = ~land_mask  # coastal cells (between thresholds) assigned to ocean

    land_biomes  = _biomes_for_zone(planner_biomes, constraints, ZONE_LAND)
    ocean_biomes = _biomes_for_zone(planner_biomes, constraints, ZONE_OCEAN)

    rng           = np.random.default_rng(rng_seed)
    assignments:   dict[str, str] = {}
    climate_seeds: list[list]     = []

    for zone_biomes, cell_mask in [(land_biomes, land_mask), (ocean_biomes, ocean_mask)]:
        surface_biomes, underground_biomes = _split_by_depth(zone_biomes, constraints)

        for biome_id in underground_biomes:
            climate_seeds.append(_underground_seed(biome_id, constraints))

        if surface_biomes:
            zone_cells = all_cells[cell_mask]
            if len(zone_cells):
                zone_features = _extract_climate_features(preview_query, zone_cells)
                seeds, labels = _kmeans_and_assign(
                    zone_features, surface_biomes, weights, constraints, cells_per_patch, rng,
                )
                climate_seeds.extend(seeds)
                for cell_idx, cell in enumerate(zone_cells):
                    assignments[f'{int(cell[0])},{int(cell[1])}'] = seeds[labels[cell_idx]][2]

    return assignments, climate_seeds


def _extract_climate_features(preview_query: np.ndarray, cells: np.ndarray) -> np.ndarray:
    """Returns (N, 3) float32 array of [temperature, humidity, erosion] for the given cells."""
    climate = preview_query[cells[:, 0], cells[:, 1], :]
    return climate[:, [TEMPERATURE_AXIS_IDX, HUMIDITY_AXIS_IDX, EROSION_AXIS_IDX]].astype(np.float32)


def _build_cell_grid(height: int, width: int, step: int, half: int) -> np.ndarray:
    """Returns (N_cells, 2) array of [row, col] for every scatter-grid cell center."""
    rows = np.arange(half, height, step)
    cols = np.arange(half, width,  step)
    grid_rows, grid_cols = np.meshgrid(rows, cols, indexing='ij')
    return np.stack([grid_rows.ravel(), grid_cols.ravel()], axis=1)


def _biomes_for_zone(
    planner_biomes: list[str],
    constraints:    dict[str, dict],
    zone:           str,
) -> list[str]:
    return [
        biome for biome in planner_biomes
        if constraints.get(biome, {}).get('zone', default_zone(biome)) == zone
    ]


def _split_by_depth(
    biomes:      list[str],
    constraints: dict[str, dict],
) -> tuple[list[str], list[str]]:
    """Splits biomes into (surface, underground) by depth_hi setting.

    Surface biomes (depth_hi ≤ 0) participate in K-means on surface cells.
    Underground/cave biomes (depth_hi > 0) skip K-means and get a midpoint anchor.
    """
    surface     = [b for b in biomes if constraints.get(b, {}).get('depth_hi', SURFACE_DEPTH) <= SURFACE_DEPTH]
    underground = [b for b in biomes if constraints.get(b, {}).get('depth_hi', SURFACE_DEPTH) > SURFACE_DEPTH]
    return surface, underground


def _underground_seed(biome_id: str, constraints: dict[str, dict]) -> list:
    """Returns [temp_center, hum_center, biome_id] midpoint anchor for a non-surface biome."""
    c           = constraints.get(biome_id, {})
    temp_center = (c.get('temperature_lo', TEMPERATURE_MIN) + c.get('temperature_hi', TEMPERATURE_MAX)) / 2
    hum_center  = (c.get('humidity_lo',    HUMIDITY_MIN)    + c.get('humidity_hi',    HUMIDITY_MAX))    / 2
    return [temp_center, hum_center, biome_id]


# ── K-means ───────────────────────────────────────────────────────────────────

def _kmeans_and_assign(
    climate_points:  np.ndarray,
    zone_biomes:     list[str],
    weights:         dict[str, float],
    constraints:     dict[str, dict],
    cells_per_patch: int,
    rng:             np.random.Generator,
) -> tuple[list[list], np.ndarray]:
    """K-means clusters in climate space (temp × hum × erosion), then assigns each cluster to a biome.

    Returns (seeds, labels):
      seeds[k]    = [temp, hum, biome_id] for cluster k  (erosion not stored — comes from biome constraint)
      labels[i]   = cluster index for climate_points[i]
    """
    num_clusters = max(len(zone_biomes), len(climate_points) // cells_per_patch)
    num_clusters = min(num_clusters, len(climate_points))

    centers, labels = _kmeans_nd(climate_points, num_clusters, rng)

    biome_for_cluster = _assign_clusters_to_biomes(centers, zone_biomes, weights, constraints)

    seeds = [
        [float(centers[cluster_idx, 0]), float(centers[cluster_idx, 1]), biome_for_cluster[cluster_idx]]
        for cluster_idx in range(num_clusters)
    ]
    return seeds, labels


def _kmeans_nd(
    points:       np.ndarray,
    num_clusters: int,
    rng:          np.random.Generator,
) -> tuple[np.ndarray, np.ndarray]:
    """Lloyd's K-means on N-dimensional float32 points. Returns (centers, labels)."""
    points  = points.astype(np.float32)
    indices = rng.choice(len(points), size=num_clusters, replace=False)
    centers = points[indices].copy()

    for _ in range(KMEANS_MAX_ITERS):
        diffs  = points[:, np.newaxis, :] - centers[np.newaxis, :, :]
        labels = np.argmin(np.sum(diffs ** 2, axis=2), axis=1)

        new_centers = np.array([
            points[labels == cluster_idx].mean(axis=0)
            if np.any(labels == cluster_idx) else centers[cluster_idx]
            for cluster_idx in range(num_clusters)
        ], dtype=np.float32)

        if np.allclose(centers, new_centers, atol=1e-4):
            break
        centers = new_centers

    return centers, labels


# ── Cluster → biome assignment ────────────────────────────────────────────────

def _assign_clusters_to_biomes(
    cluster_centers: np.ndarray,
    zone_biomes:     list[str],
    weights:         dict[str, float],
    constraints:     dict[str, dict],
) -> list[str]:
    """Assigns each cluster to a biome.

    Clusters and biomes are both sorted by temperature (axis 0), then aligned by weight.
    Cold biomes receive cold-temperature clusters; warm biomes get warm clusters.
    """
    num_clusters    = len(cluster_centers)
    sorted_biomes   = sorted(zone_biomes, key=lambda biome_id: _biome_temp_center(biome_id, constraints))
    cluster_by_temp = np.argsort(cluster_centers[:, 0]).tolist()
    biome_counts    = _proportional_cluster_counts(sorted_biomes, weights, num_clusters)

    cluster_biomes = [''] * num_clusters
    rank = 0
    for biome, count in zip(sorted_biomes, biome_counts):
        for offset in range(count):
            if rank + offset < num_clusters:
                cluster_biomes[cluster_by_temp[rank + offset]] = biome
        rank += count

    return cluster_biomes


def _biome_temp_center(biome_id: str, constraints: dict[str, dict]) -> float:
    lo = constraints.get(biome_id, {}).get('temperature_lo', -1.0)
    hi = constraints.get(biome_id, {}).get('temperature_hi',  1.0)
    return (lo + hi) / 2


def _proportional_cluster_counts(
    sorted_biomes: list[str],
    weights:       dict[str, float],
    num_clusters:  int,
) -> list[int]:
    """Proportional allocation of clusters to biomes, minimum 1 per biome."""
    num_clusters = max(num_clusters, len(sorted_biomes))
    total_weight = sum(weights.get(biome, 1.0) for biome in sorted_biomes) or 1.0
    exact_counts = [weights.get(biome, 1.0) / total_weight * num_clusters for biome in sorted_biomes]
    counts       = [max(1, int(exact)) for exact in exact_counts]

    shortfall = num_clusters - sum(counts)
    if shortfall > 0:
        remainder_order = sorted(
            range(len(sorted_biomes)),
            key=lambda biome_idx: exact_counts[biome_idx] - counts[biome_idx],
            reverse=True,
        )
        for idx in range(shortfall):
            counts[remainder_order[idx % len(remainder_order)]] += 1

    return counts


# ── Section (embedded in World Map tab) ──────────────────────────────────────

def render_auto_layout_section(
    planner_biomes: list[str],
    biome_weights:  dict[str, float],
    constraints:    dict[str, dict],
    preview_query:  np.ndarray,
) -> None:
    with st.expander('Auto Layout Generator'):
        st.caption(
            'K-means clusters biomes in temperature×humidity×erosion space. '
            'Each cluster becomes one TerraBlender anchor — no interleaving. '
            'Non-surface biomes (depth_hi > 0) skip K-means and get a midpoint anchor. '
            'Overwrites Paint Map assignments; map above updates immediately.'
        )
        cells_per_patch = st.slider(
            'Cells per cluster',
            MIN_CELLS_PER_PATCH, MAX_CELLS_PER_PATCH, DEFAULT_CELLS_PER_PATCH, step=2,
            key='auto_cells_per_patch',
        )
        rng_seed = st.number_input(
            'Random seed', min_value=0, max_value=99999, value=42, step=1,
            key='auto_rng_seed',
        )
        if st.button('Generate', type='primary', key='auto_generate'):
            with st.spinner('Computing K-means layout...'):
                assignments, climate_seeds = auto_distribute_biomes(
                    planner_biomes, biome_weights, constraints, preview_query,
                    cells_per_patch, int(rng_seed),
                )
            st.session_state['paint_assignments'] = assignments
            st.session_state['paint_seeds']       = climate_seeds
            save_paint_assignments(assignments)
            save_paint_seeds(climate_seeds)
            st.rerun()
