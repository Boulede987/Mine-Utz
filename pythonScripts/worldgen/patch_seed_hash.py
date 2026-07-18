"""
worldgen/patch_seed_hash.py
=============================
Deterministic per-cell seed derivation for the patch-growth engine (patch_engine.py).

Must stay bit-identical to the Java PatchSeedHasher (companion-mods/mineutze-worldgen)
so the Python preview and the real in-game BiomeSource derive the same germination
choices, spacing, and tie-break priorities from the same (world_seed, coords) -- a
Python-only hash formula would make the Streamlit preview a different algorithm wearing
the same UI, not a preview of the real thing.

Uses a splitmix64-style bit mix rather than seeding a general-purpose PRNG per cell:
per-cell PRNGs seeded from small, nearby integer coordinates (e.g. Java's
`new Random(x, y, z)` idiom) have poor avalanche behaviour -- neighbouring cells can
produce correlated first outputs. splitmix64's finalizer round is a standard, well-mixed
alternative for exactly this "hash a handful of small integers into an unrelated-looking
64-bit value" use case.
"""

UNSIGNED_64_BIT_MASK = (1 << 64) - 1

# Constants from the reference splitmix64 finalizer (Vigna & Steele) -- not modpack
# specific, do not rename to something more "descriptive": they are the algorithm.
_SPLITMIX64_INCREMENT  = 0x9E3779B97F4A7C15
_SPLITMIX64_MULTIPLIER_1 = 0xBF58476D1CE4E5B9
_SPLITMIX64_MULTIPLIER_2 = 0x94D049BB133111EB


def mix_64_bits(seed: int) -> int:
    """One splitmix64 finalizer round: hashes seed into a well-avalanched 64-bit value."""
    seed = (seed + _SPLITMIX64_INCREMENT) & UNSIGNED_64_BIT_MASK
    mixed = seed
    mixed = ((mixed ^ (mixed >> 30)) * _SPLITMIX64_MULTIPLIER_1) & UNSIGNED_64_BIT_MASK
    mixed = ((mixed ^ (mixed >> 27)) * _SPLITMIX64_MULTIPLIER_2) & UNSIGNED_64_BIT_MASK
    mixed = mixed ^ (mixed >> 31)
    return mixed & UNSIGNED_64_BIT_MASK


def cell_seed(world_seed: int, cell_x: int, cell_y: int, cell_z: int) -> int:
    """Deterministic per-cell seed. cell_y is 0 for the 2D surface tier."""
    mixed = world_seed & UNSIGNED_64_BIT_MASK
    for coordinate in (cell_x, cell_y, cell_z):
        mixed = mix_64_bits(mixed ^ (coordinate & UNSIGNED_64_BIT_MASK))
    return mixed


def unit_interval_from_seed(seed: int) -> float:
    """[0, 1) float derived from a seed -- used for germination rolls, patch-size
    sampling, and tie-break priorities."""
    top_32_bits = seed & 0xFFFFFFFF
    return top_32_bits / float(1 << 32)
