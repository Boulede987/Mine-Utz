"""
worldgen/biome_grid.py
=======================
Grid-based climate span assignment.

Partitions the temperature × humidity plane into rectangular cells and assigns
each cell to exactly one biome.  No two biomes compete for the same cell, so
borders are always clean and the weight slider is directly proportional to
coverage within a biome's eligible temperature/humidity range.

Land biomes and ocean biomes use separate grids because they live in different
continentalness ranges and should not compete with each other.
"""

import random
from dataclasses import dataclass

import numpy as np

ZONE_LAND  = 'land'
ZONE_OCEAN = 'ocean'

LAND_TEMPERATURE_BANDS  = 8   # -1.5 to 1.5 divided into 8 equal bands
LAND_HUMIDITY_BANDS     = 6   # -1.0 to 1.0 divided into 6 equal bands
OCEAN_TEMPERATURE_BANDS = 5   # -1.5 to 1.5 divided into 5 equal bands

TEMPERATURE_MIN = -1.5
TEMPERATURE_MAX =  1.5
HUMIDITY_MIN    = -1.0
HUMIDITY_MAX    =  1.0

LAND_CONTINENTALNESS_LO  =  0.03
LAND_CONTINENTALNESS_HI  =  1.0
OCEAN_CONTINENTALNESS_LO = -1.1
OCEAN_CONTINENTALNESS_HI = -0.11

EROSION_MIN   = -1.0
EROSION_MAX   =  1.0
DEPTH_MIN     = -1.0
DEPTH_MAX     =  1.5
SURFACE_DEPTH =  0.0
WEIRDNESS_MIN = -1.0
WEIRDNESS_MAX =  1.0

# Fixed seed so cell ordering (and thus biome borders) is reproducible across
# weight changes.  Change this to get a different layout with the same weights.
CELL_SHUFFLE_SEED  = 7
ANCHORS_PER_CELL   = 16  # erosion×weirdness point anchors per grid cell


@dataclass(frozen=True)
class GridCell:
    temperature_lo:     float
    temperature_hi:     float
    humidity_lo:        float
    humidity_hi:        float
    continentalness_lo: float
    continentalness_hi: float


def tile_map_to_entries(cell_to_biome: dict[GridCell, str]) -> list[dict]:
    """Converts an explicit {GridCell: biome_id} mapping to TerraBlender entries."""
    biome_to_cells: dict[str, list[GridCell]] = {}
    for cell, biome_id in cell_to_biome.items():
        biome_to_cells.setdefault(biome_id, []).append(cell)
    return _assignments_to_entries(biome_to_cells, {})


def generate_grid_entries(
    planner_biomes: list[str],
    weights:        dict[str, float],
    constraints:    dict[str, dict],
) -> list[dict]:
    """Builds climate parameter entries from the grid.  Replaces BoP mirror loading."""
    land_biomes  = _biomes_for_zone(planner_biomes, constraints, ZONE_LAND)
    ocean_biomes = _biomes_for_zone(planner_biomes, constraints, ZONE_OCEAN)

    land_assignments  = _assign_cells(build_land_cells(),  land_biomes,  weights, constraints)
    ocean_assignments = _assign_cells(build_ocean_cells(), ocean_biomes, weights, constraints)

    return _assignments_to_entries({**land_assignments, **ocean_assignments}, constraints)


def build_land_cells() -> list[GridCell]:
    """All surface land cells (temperature × humidity grid)."""
    temp_edges = np.linspace(TEMPERATURE_MIN, TEMPERATURE_MAX, LAND_TEMPERATURE_BANDS + 1)
    hum_edges  = np.linspace(HUMIDITY_MIN,    HUMIDITY_MAX,    LAND_HUMIDITY_BANDS + 1)
    return [
        GridCell(
            temperature_lo=float(temp_edges[temp_idx]),
            temperature_hi=float(temp_edges[temp_idx + 1]),
            humidity_lo=float(hum_edges[hum_idx]),
            humidity_hi=float(hum_edges[hum_idx + 1]),
            continentalness_lo=LAND_CONTINENTALNESS_LO,
            continentalness_hi=LAND_CONTINENTALNESS_HI,
        )
        for temp_idx in range(LAND_TEMPERATURE_BANDS)
        for hum_idx  in range(LAND_HUMIDITY_BANDS)
    ]


def build_ocean_cells() -> list[GridCell]:
    """All surface ocean cells (temperature only; humidity spans full range)."""
    temp_edges = np.linspace(TEMPERATURE_MIN, TEMPERATURE_MAX, OCEAN_TEMPERATURE_BANDS + 1)
    return [
        GridCell(
            temperature_lo=float(temp_edges[temp_idx]),
            temperature_hi=float(temp_edges[temp_idx + 1]),
            humidity_lo=HUMIDITY_MIN,
            humidity_hi=HUMIDITY_MAX,
            continentalness_lo=OCEAN_CONTINENTALNESS_LO,
            continentalness_hi=OCEAN_CONTINENTALNESS_HI,
        )
        for temp_idx in range(OCEAN_TEMPERATURE_BANDS)
    ]


def default_zone(biome_id: str) -> str:
    """Ocean if the biome ID contains 'ocean' or 'sea', land otherwise."""
    lower = biome_id.lower()
    if 'ocean' in lower or 'sea' in lower:
        return ZONE_OCEAN
    return ZONE_LAND


# ── Internal helpers ──────────────────────────────────────────────────────────

def _biomes_for_zone(
    planner_biomes: list[str],
    constraints:    dict[str, dict],
    zone:           str,
) -> list[str]:
    return [
        biome for biome in planner_biomes
        if constraints.get(biome, {}).get('zone', default_zone(biome)) == zone
    ]


def _assign_cells(
    cells:       list[GridCell],
    biomes:      list[str],
    weights:     dict[str, float],
    constraints: dict[str, dict],
) -> dict[str, list[GridCell]]:
    """Proportionally assigns cells to biomes in two passes.

    Pass 1: constrained biomes claim cells from their eligible range proportionally.
    Pass 2: unconstrained biomes split whatever cells remain proportionally.

    This prevents unconstrained biomes (eligible everywhere) from starving constrained
    ones by always winning the greedy race.
    """
    if not biomes or not cells:
        return {biome: [] for biome in biomes}

    constrained_biomes   = [b for b in biomes if _has_range_constraint(b, constraints)]
    unconstrained_biomes = [b for b in biomes if not _has_range_constraint(b, constraints)]

    assignments  = {biome: [] for biome in biomes}
    total_weight = sum(weights.get(b, 1.0) for b in biomes) or 1.0
    shuffled     = _shuffled_cells(cells)

    constrained_targets = _proportional_targets(constrained_biomes, weights, total_weight, len(cells))
    remaining_cells     = _greedy_fill(shuffled, constrained_biomes, constrained_targets, constraints, assignments)

    if unconstrained_biomes and remaining_cells:
        unc_total_weight    = sum(weights.get(b, 1.0) for b in unconstrained_biomes) or 1.0
        unc_targets         = _proportional_targets(unconstrained_biomes, weights, unc_total_weight, len(remaining_cells))
        _greedy_fill(remaining_cells, unconstrained_biomes, unc_targets, constraints, assignments)

    return assignments


def _proportional_targets(
    biomes:       list[str],
    weights:      dict[str, float],
    total_weight: float,
    total_cells:  int,
) -> dict[str, int]:
    return {
        biome: max(0, round(weights.get(biome, 1.0) / total_weight * total_cells))
        for biome in biomes
    }


def _greedy_fill(
    cells:        list[GridCell],
    biomes:       list[str],
    targets:      dict[str, int],
    constraints:  dict[str, dict],
    assignments:  dict[str, list[GridCell]],
) -> list[GridCell]:
    """Greedily assigns cells to biomes up to their targets. Returns unassigned cells."""
    remaining_demand = dict(targets)
    unassigned       = []

    for cell in cells:
        best = _pick_best_biome(cell, biomes, remaining_demand, constraints)
        if best is None:
            unassigned.append(cell)
        else:
            assignments[best].append(cell)
            remaining_demand[best] -= 1

    return unassigned


def _pick_best_biome(
    cell:             GridCell,
    biomes:           list[str],
    remaining_demand: dict[str, int],
    constraints:      dict[str, dict],
) -> str | None:
    """Eligible biome with the highest remaining demand, or None if all demands are met."""
    eligible = [
        biome for biome in biomes
        if remaining_demand[biome] > 0
        and _cell_overlaps_constraint(cell, constraints.get(biome, {}))
    ]
    if not eligible:
        return None
    return max(eligible, key=lambda biome: remaining_demand[biome])


def _has_range_constraint(biome_id: str, constraints: dict[str, dict]) -> bool:
    """True when the biome has a non-default temperature or humidity range."""
    constraint = constraints.get(biome_id, {})
    epsilon    = 0.01
    return (
        abs(constraint.get('temperature_lo', TEMPERATURE_MIN) - TEMPERATURE_MIN) > epsilon or
        abs(constraint.get('temperature_hi', TEMPERATURE_MAX) - TEMPERATURE_MAX) > epsilon or
        abs(constraint.get('humidity_lo',    HUMIDITY_MIN)    - HUMIDITY_MIN)    > epsilon or
        abs(constraint.get('humidity_hi',    HUMIDITY_MAX)    - HUMIDITY_MAX)    > epsilon
    )


def _cell_overlaps_constraint(cell: GridCell, constraint: dict) -> bool:
    """True when the cell's temperature and humidity ranges overlap the constraint's preferred ranges."""
    temp_lo = constraint.get('temperature_lo', TEMPERATURE_MIN)
    temp_hi = constraint.get('temperature_hi', TEMPERATURE_MAX)
    hum_lo  = constraint.get('humidity_lo',    HUMIDITY_MIN)
    hum_hi  = constraint.get('humidity_hi',    HUMIDITY_MAX)
    if cell.temperature_hi <= temp_lo or cell.temperature_lo >= temp_hi:
        return False
    if cell.humidity_hi <= hum_lo or cell.humidity_lo >= hum_hi:
        return False
    return True


def _shuffled_cells(cells: list[GridCell]) -> list[GridCell]:
    """Deterministically shuffled copy — same seed keeps borders stable across weight changes."""
    shuffled = list(cells)
    random.seed(CELL_SHUFFLE_SEED)
    random.shuffle(shuffled)
    return shuffled


def _assignments_to_entries(
    assignments: dict[str, list[GridCell]],
    constraints: dict[str, dict],
) -> list[dict]:
    return [
        entry
        for biome_id, cells in assignments.items()
        for cell in cells
        for entry in _cell_to_entries(biome_id, cell, constraints.get(biome_id, {}))
    ]


def _cell_to_entries(biome_id: str, cell: GridCell, constraint: dict) -> list[dict]:
    """Creates ANCHORS_PER_CELL point anchors for one grid cell.

    Temperature and humidity use the cell midpoint so Voronoi boundaries align with
    cell edges.  Erosion and weirdness are sampled randomly within the biome's
    constrained range (defaulting to the full axis range), seeded from the cell's
    position so borders stay stable across weight changes.
    """
    temp_mid     = (cell.temperature_lo + cell.temperature_hi) / 2.0
    hum_mid      = (cell.humidity_lo    + cell.humidity_hi)    / 2.0
    erosion_lo   = constraint.get('erosion_lo',   EROSION_MIN)
    erosion_hi   = constraint.get('erosion_hi',   EROSION_MAX)
    depth_lo     = constraint.get('depth_lo',     SURFACE_DEPTH)
    depth_hi     = constraint.get('depth_hi',     SURFACE_DEPTH)
    weirdness_lo = constraint.get('weirdness_lo', WEIRDNESS_MIN)
    weirdness_hi = constraint.get('weirdness_hi', WEIRDNESS_MAX)
    cell_seed    = f'{CELL_SHUFFLE_SEED}:{cell.temperature_lo}:{cell.humidity_lo}:{cell.continentalness_lo}'
    rng          = random.Random(cell_seed)
    entries      = []
    for _ in range(ANCHORS_PER_CELL):
        erosion   = rng.uniform(erosion_lo,   erosion_hi)
        weirdness = rng.uniform(weirdness_lo, weirdness_hi)
        entries.append({
            'biome': biome_id,
            'parameters': {
                'temperature':     [temp_mid,  temp_mid],
                'humidity':        [hum_mid,   hum_mid],
                'continentalness': [cell.continentalness_lo, cell.continentalness_hi],
                'erosion':         [erosion,   erosion],
                'depth':           [depth_lo,  depth_hi],
                'weirdness':       [weirdness, weirdness],
            },
        })
    return entries
