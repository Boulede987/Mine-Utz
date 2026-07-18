"""
worldgen/patch_engine.py
=========================
Seeded patch-growth biome placement engine. Python prototype of the algorithm that gets
ported to Java (companion-mods/mineutze-worldgen's biomesource/patch package) for the
real in-game BiomeSource -- iterating here is cheaper than a Gradle rebuild per tweak,
but only useful if both sides genuinely share the same seed-hash formula, priority
score, and termination rule (see patch_seed_hash.py's docstring).

Replaces climate-anchor nearest-neighbour placement (biome_grid.py,
biome_nearest_neighbour.py) entirely: biomes no longer occupy a position in climate
space, they declare terrain-class eligibility, a patch size range, and neighbor
preferences. See DESIGN.md for the full rationale -- Voronoi cell shape from
nearest-neighbour is an accident of point density, not something you can size or shape;
patches are grown explicitly instead.

Determinism (DESIGN.md Sec 6): resolve_region() must never depend on iteration/scan
order, because in the real game, chunks (and therefore biome queries) resolve in
whatever order a player explores in, not a fixed scan. Two techniques make that true
here:
  - Germination is decided per-cell by a *local-maximum* rule -- a cell only germinates
    a patch if no other cell within its (bounded) neighbourhood has a strictly higher,
    independently-precomputed priority. That's a pure comparison over immutable data, so
    it can't depend on which cell got evaluated first.
  - Growth is a global best-first priority queue where every score includes a tiny
    deterministic per-(cell, patch) tie-break derived from the world seed, so even exact
    score ties resolve the same way regardless of push order.
See run_determinism_self_test() for a check that two different scan orders produce a
byte-identical result.
"""

import heapq
import itertools
from dataclasses import dataclass, field

import numpy as np

from worldgen.patch_seed_hash import cell_seed, mix_64_bits, unit_interval_from_seed
from worldgen.terrain_classifier import TerrainClass

CARDINAL_NEIGHBOR_OFFSETS = ((-1, 0), (1, 0), (0, -1), (0, 1))

PREFERRED_NEIGHBOR_SCORE_BONUS   = 0.5
FORBIDDEN_NEIGHBOR_SCORE_PENALTY = 0.5
TIE_BREAK_SCORE_SCALE            = 1e-6

MINIMUM_SPACING_CELLS = 1

# A germination point deep inside what will become a much larger neighboring patch's
# territory can survive its own local-maximum check (nothing else was within ITS
# spacing radius at germination time) and then get boxed in almost immediately once the
# surrounding patch grows around it -- a small, isolated "island" of one biome inside
# another. Real, reported symptom, not a hypothetical: a component smaller than this
# fraction of its own biome's expected minimum size is noise, not an intentionally
# small patch, and gets dissolved into whichever neighboring biome borders it most (see
# dissolve_small_islands). Only ever reassigns into a biome that's terrain-class-eligible
# for every cell involved -- dissolving into an ineligible biome would silently undo the
# terrain-class hard gate. Must stay in lockstep with PatchGrowthEngine's Java copy.
SMALL_ISLAND_DISSOLVE_FRACTION = 0.3

# cell_seed's cell_y parameter doubles as a hash "namespace" selector here: 0 is used for
# germination-candidate selection (biome pick + priority), 1 for patch-identity seeding
# (see instantiate_patches) -- keeps the two hash uses from colliding.
GERMINATION_CANDIDATE_HASH_NAMESPACE = 0
PATCH_IDENTITY_HASH_NAMESPACE        = 1


@dataclass(frozen=True)
class BiomeConfig:
    biome_id: str
    terrain_classes: frozenset
    size_min_blocks: float
    size_max_blocks: float
    weight: float = 1.0
    preferred_neighbors: frozenset = field(default_factory=frozenset)
    forbidden_neighbors: frozenset = field(default_factory=frozenset)


@dataclass(frozen=True)
class GerminationCandidate:
    """A cell's own, neighbor-agnostic answer to "if I were to germinate a patch here,
    which biome, how far must other patches stay, and how strongly do I want to?"."""
    biome: BiomeConfig
    spacing_cells: int
    priority: float


@dataclass
class GrowingPatch:
    patch_id: int
    biome: BiomeConfig
    target_radius_blocks: float
    # Derived from the patch's germination cell coordinates, not patch_id -- patch_id is
    # only a dict key and depends on germination_points list order, which depends on
    # scan_order. Scoring must depend on something order-independent instead (see
    # instantiate_patches / compute_cell_score).
    identity_seed: int
    cell_count: int = 0


def eligible_biomes_for_terrain_class(biomes: list, terrain_class: TerrainClass) -> list:
    return [biome for biome in biomes if terrain_class in biome.terrain_classes]


def pick_weighted_biome(candidates: list, seed: int) -> BiomeConfig:
    total_weight = sum(biome.weight for biome in candidates) or 1.0
    roll = unit_interval_from_seed(seed) * total_weight
    running_total = 0.0
    for biome in candidates:
        running_total += biome.weight
        if roll <= running_total:
            return biome
    return candidates[-1]


def spacing_radius_in_cells(biome: BiomeConfig, cell_size_blocks: float) -> int:
    return max(MINIMUM_SPACING_CELLS, round(biome.size_min_blocks / (2 * cell_size_blocks)))


def precompute_germination_candidates(
    world_seed: int,
    biomes: list,
    terrain_grid: np.ndarray,
    cell_size_blocks: float,
    scan_order: list,
) -> dict:
    """Pure function of (world_seed, row, col) for every cell -- never reads any other
    cell's result, so evaluation order (scan_order) cannot affect the output."""
    candidates_by_cell = {}
    for row, col in scan_order:
        eligible = eligible_biomes_for_terrain_class(biomes, terrain_grid[row, col])
        if not eligible:
            continue
        seed = cell_seed(world_seed, col, GERMINATION_CANDIDATE_HASH_NAMESPACE, row)
        chosen_biome = pick_weighted_biome(eligible, seed)
        candidates_by_cell[(row, col)] = GerminationCandidate(
            biome=chosen_biome,
            spacing_cells=spacing_radius_in_cells(chosen_biome, cell_size_blocks),
            priority=unit_interval_from_seed(mix_64_bits(seed)),
        )
    return candidates_by_cell


def neighbor_offsets_within_radius(radius: int):
    for delta_row in range(-radius, radius + 1):
        for delta_col in range(-radius, radius + 1):
            if delta_row == 0 and delta_col == 0:
                continue
            yield delta_row, delta_col


def is_local_maximum(
    row: int,
    col: int,
    candidate: GerminationCandidate,
    candidates_by_cell: dict,
    lookahead_radius_cells: int,
) -> bool:
    """A cell only germinates if no other cell within the symmetric spacing radius has
    strictly higher priority -- this is what makes germination order-independent instead
    of "whichever cell was visited first"."""
    for delta_row, delta_col in neighbor_offsets_within_radius(lookahead_radius_cells):
        neighbor = candidates_by_cell.get((row + delta_row, col + delta_col))
        if neighbor is None:
            continue
        symmetric_radius = max(candidate.spacing_cells, neighbor.spacing_cells)
        within_radius = max(abs(delta_row), abs(delta_col)) <= symmetric_radius
        if within_radius and neighbor.priority > candidate.priority:
            return False
    return True


def select_germination_points(candidates_by_cell: dict) -> list:
    """Returns [(row, col, GerminationCandidate), ...] for every cell that wins its
    local-maximum check. Order-independent: see is_local_maximum's docstring."""
    lookahead_radius_cells = max(
        (candidate.spacing_cells for candidate in candidates_by_cell.values()), default=0
    )
    return [
        (row, col, candidate)
        for (row, col), candidate in candidates_by_cell.items()
        if is_local_maximum(row, col, candidate, candidates_by_cell, lookahead_radius_cells)
    ]


def target_radius_from_seed(candidate: GerminationCandidate) -> float:
    size_range = candidate.biome.size_max_blocks - candidate.biome.size_min_blocks
    return candidate.biome.size_min_blocks / 2.0 + candidate.priority * size_range / 2.0


def instantiate_patches(germination_points: list, world_seed: int) -> tuple:
    """Returns (patches_by_id, resolved_cells) with each germination point assigned as
    its own patch's first cell. patch_id is just a dict key (assignment order depends on
    germination_points' order, which depends on scan_order) -- identity_seed is the
    order-independent value scoring must use instead, derived purely from the patch's own
    germination coordinates."""
    patches_by_id = {}
    resolved_cells = {}
    for row, col, candidate in germination_points:
        patch_id = len(patches_by_id)
        patches_by_id[patch_id] = GrowingPatch(
            patch_id=patch_id,
            biome=candidate.biome,
            target_radius_blocks=target_radius_from_seed(candidate),
            identity_seed=cell_seed(world_seed, col, PATCH_IDENTITY_HASH_NAMESPACE, row),
            cell_count=1,
        )
        resolved_cells[(row, col)] = candidate.biome.biome_id
    return patches_by_id, resolved_cells


def compute_cell_score(row: int, col: int, patch: GrowingPatch, resolved_cells: dict) -> float:
    score = patch.biome.weight
    for delta_row, delta_col in CARDINAL_NEIGHBOR_OFFSETS:
        neighbor_biome_id = resolved_cells.get((row + delta_row, col + delta_col))
        if neighbor_biome_id is None:
            continue
        if neighbor_biome_id in patch.biome.preferred_neighbors:
            score += PREFERRED_NEIGHBOR_SCORE_BONUS
        if neighbor_biome_id in patch.biome.forbidden_neighbors:
            score -= FORBIDDEN_NEIGHBOR_SCORE_PENALTY
    # Deterministic tie-break unique per (cell, patch) -- makes real score ties between
    # competing patches vanishingly unlikely, so heap-pop order never depends on push
    # order. Keyed off patch.identity_seed (order-independent, see instantiate_patches),
    # not patch_id.
    tie_break_seed = cell_seed(patch.identity_seed, col, 0, row)
    return score + unit_interval_from_seed(tie_break_seed) * TIE_BREAK_SCORE_SCALE


def patch_radius_from_cell_count(cell_count: int, cell_size_blocks: float) -> float:
    """Approximates patch radius from assigned cell count, treating the patch as a disc."""
    area_blocks_squared = cell_count * (cell_size_blocks ** 2)
    return (area_blocks_squared / np.pi) ** 0.5


def push_eligible_neighbors(
    frontier: list,
    push_counter: itertools.count,
    row: int,
    col: int,
    patch: GrowingPatch,
    resolved_cells: dict,
    terrain_grid: np.ndarray,
) -> None:
    rows, cols = terrain_grid.shape
    for delta_row, delta_col in CARDINAL_NEIGHBOR_OFFSETS:
        neighbor_row, neighbor_col = row + delta_row, col + delta_col
        if not (0 <= neighbor_row < rows and 0 <= neighbor_col < cols):
            continue
        if (neighbor_row, neighbor_col) in resolved_cells:
            continue
        if terrain_grid[neighbor_row, neighbor_col] not in patch.biome.terrain_classes:
            continue
        score = compute_cell_score(neighbor_row, neighbor_col, patch, resolved_cells)
        heapq.heappush(frontier, (-score, next(push_counter), neighbor_row, neighbor_col, patch.patch_id))


def build_initial_frontier(
    germination_points: list, patches_by_id: dict, resolved_cells: dict, terrain_grid: np.ndarray
) -> tuple:
    frontier = []
    push_counter = itertools.count()
    for patch_id, (row, col, _candidate) in enumerate(germination_points):
        push_eligible_neighbors(frontier, push_counter, row, col, patches_by_id[patch_id], resolved_cells, terrain_grid)
    return frontier, push_counter


def grow_until_frontier_empty(
    frontier: list,
    push_counter: itertools.count,
    patches_by_id: dict,
    resolved_cells: dict,
    terrain_grid: np.ndarray,
    cell_size_blocks: float,
) -> None:
    """Global best-first growth: heap pop order is a pure function of scores (themselves
    seed-derived), never of push order -- see compute_cell_score's tie-break comment."""
    while frontier:
        _negative_score, _push_order, row, col, patch_id = heapq.heappop(frontier)
        if (row, col) in resolved_cells:
            continue  # a different patch already claimed this cell
        patch = patches_by_id[patch_id]
        prospective_radius = patch_radius_from_cell_count(patch.cell_count + 1, cell_size_blocks)
        if prospective_radius > patch.target_radius_blocks:
            continue  # this patch has already reached its sampled target size
        resolved_cells[(row, col)] = patch.biome.biome_id
        patch.cell_count += 1
        push_eligible_neighbors(frontier, push_counter, row, col, patch, resolved_cells, terrain_grid)


def flood_fill_same_biome_component(start: tuple, biome_id: str, resolved_cells: dict, visited: set) -> list:
    component = []
    frontier = [start]
    visited.add(start)
    while frontier:
        row, col = frontier.pop()
        component.append((row, col))
        for delta_row, delta_col in CARDINAL_NEIGHBOR_OFFSETS:
            neighbor = (row + delta_row, col + delta_col)
            if neighbor in visited or resolved_cells.get(neighbor) != biome_id:
                continue
            visited.add(neighbor)
            frontier.append(neighbor)
    return component


def small_island_threshold(biome: BiomeConfig, cell_size_blocks: float) -> int:
    """A component is "too small" if it's a meaningful fraction below what even the
    smallest legitimate roll of its own biome's [size_min, size_max] range would
    produce -- see SMALL_ISLAND_DISSOLVE_FRACTION's module-level comment."""
    min_radius_cells = biome.size_min_blocks / 2.0 / cell_size_blocks
    expected_min_cells = np.pi * min_radius_cells ** 2
    return max(1, round(expected_min_cells * SMALL_ISLAND_DISSOLVE_FRACTION))


def pick_dissolve_replacement(
    component: list, original_biome_id: str, resolved_cells: dict, terrain_grid: np.ndarray, config_by_id: dict
) -> str | None:
    component_cells = set(component)
    border_counts: dict[str, int] = {}
    for row, col in component:
        for delta_row, delta_col in CARDINAL_NEIGHBOR_OFFSETS:
            neighbor = (row + delta_row, col + delta_col)
            if neighbor in component_cells:
                continue
            neighbor_biome_id = resolved_cells.get(neighbor)
            if neighbor_biome_id is None or neighbor_biome_id == original_biome_id:
                continue
            border_counts[neighbor_biome_id] = border_counts.get(neighbor_biome_id, 0) + 1

    for candidate_id, _count in sorted(border_counts.items(), key=lambda item: item[1], reverse=True):
        candidate = config_by_id.get(candidate_id)
        if candidate is None:
            continue
        if all(terrain_grid[row, col] in candidate.terrain_classes for row, col in component):
            return candidate_id
    return None


def dissolve_small_islands(
    resolved_cells: dict, terrain_grid: np.ndarray, biomes: list, cell_size_blocks: float
) -> None:
    """Post-processing pass: finds contiguous same-biome components too small to be a
    real patch and reassigns them to whichever bordering biome is both dominant and
    terrain-class-eligible for every cell involved. Mutates resolved_cells in place.

    All decisions read from a frozen snapshot taken before any reassignment, and every
    reassignment is applied only after every component has been decided -- otherwise two
    adjacent small islands' border tallies would depend on which one got processed
    first, breaking the module's order-independence guarantee (see module docstring).
    """
    config_by_id = {biome.biome_id: biome for biome in biomes}
    snapshot = dict(resolved_cells)
    visited: set = set()
    reassignments: dict = {}
    for cell in list(snapshot.keys()):
        if cell in visited:
            continue
        biome_id = snapshot[cell]
        component = flood_fill_same_biome_component(cell, biome_id, snapshot, visited)

        biome = config_by_id.get(biome_id)
        if biome is None or len(component) >= small_island_threshold(biome, cell_size_blocks):
            continue
        replacement = pick_dissolve_replacement(component, biome_id, snapshot, terrain_grid, config_by_id)
        if replacement is not None:
            for component_cell in component:
                reassignments[component_cell] = replacement
    resolved_cells.update(reassignments)


def resolve_region(
    world_seed: int,
    biomes: list,
    terrain_grid: np.ndarray,
    cell_size_blocks: float = 16.0,
    scan_order: list = None,
) -> dict:
    """Resolves every cell in terrain_grid to a biome id. A cell is left unresolved
    (absent from the result) if no configured biome's terrain_classes cover it and no
    neighboring patch's growth reaches it -- expected when terrain-class coverage is
    incomplete, not a bug.

    scan_order, if given, only changes the *evaluation* order of the germination
    precompute pass -- per the module docstring, it must not (and does not) change the
    final result. This is what run_determinism_self_test() exploits.
    """
    rows, cols = terrain_grid.shape
    if scan_order is None:
        scan_order = [(row, col) for row in range(rows) for col in range(cols)]

    candidates_by_cell = precompute_germination_candidates(
        world_seed, biomes, terrain_grid, cell_size_blocks, scan_order
    )
    germination_points = select_germination_points(candidates_by_cell)
    patches_by_id, resolved_cells = instantiate_patches(germination_points, world_seed)
    frontier, push_counter = build_initial_frontier(germination_points, patches_by_id, resolved_cells, terrain_grid)
    grow_until_frontier_empty(frontier, push_counter, patches_by_id, resolved_cells, terrain_grid, cell_size_blocks)
    dissolve_small_islands(resolved_cells, terrain_grid, biomes, cell_size_blocks)
    return resolved_cells


def _build_test_terrain_grid(grid_size: int, random_seed: int) -> np.ndarray:
    terrain_choices = list(TerrainClass)
    indices = np.random.default_rng(random_seed).integers(0, len(terrain_choices), size=(grid_size, grid_size))
    return np.array([[terrain_choices[index] for index in row] for row in indices], dtype=object)


def _build_test_biomes() -> list:
    return [
        BiomeConfig('test:plains', frozenset({TerrainClass.FLAT_LAND}), 48, 160, weight=1.0),
        BiomeConfig('test:hills', frozenset({TerrainClass.HILLY}), 32, 96, weight=1.0,
                    preferred_neighbors=frozenset({'test:plains'})),
        BiomeConfig('test:peaks', frozenset({TerrainClass.MOUNTAINOUS}), 32, 128, weight=0.6,
                    forbidden_neighbors=frozenset({'test:desert'})),
        BiomeConfig('test:desert', frozenset({TerrainClass.FLAT_LAND, TerrainClass.COAST}), 48, 160, weight=0.8,
                    forbidden_neighbors=frozenset({'test:peaks'})),
        BiomeConfig('test:beach', frozenset({TerrainClass.COAST}), 16, 48, weight=1.0),
        BiomeConfig('test:sea', frozenset({TerrainClass.OCEAN}), 64, 200, weight=1.0),
    ]


def run_determinism_self_test(world_seed: int = 12345, grid_size: int = 48, terrain_random_seed: int = 7) -> bool:
    """Resolves the same synthetic region twice via two different scan orders and asserts
    the resulting cell->biome maps are identical -- the discovery-order test DESIGN.md
    Sec 6 calls for, run here in pure Python before any Java exists."""
    terrain_grid = _build_test_terrain_grid(grid_size, terrain_random_seed)
    biomes = _build_test_biomes()

    rows, cols = terrain_grid.shape
    row_major_order = [(row, col) for row in range(rows) for col in range(cols)]
    shuffled_order = list(row_major_order)
    np.random.default_rng(99).shuffle(shuffled_order)

    result_row_major = resolve_region(world_seed, biomes, terrain_grid, scan_order=row_major_order)
    result_shuffled = resolve_region(world_seed, biomes, terrain_grid, scan_order=shuffled_order)
    return result_row_major == result_shuffled


if __name__ == '__main__':
    passed = run_determinism_self_test()
    print('Determinism self-test:', 'PASS' if passed else 'FAIL')
    if not passed:
        raise SystemExit(1)
