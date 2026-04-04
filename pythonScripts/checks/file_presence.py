"""
checks/file_presence.py
=======================
Verifies all expected extraction output files are present and non-empty.
"""

from pathlib import Path

EXPECTED_FILES = [
    'items.txt',
    'blocks.txt',
    'mobs.txt',
    'biomes.txt',
    'structures.txt',
    'recipes.txt',
    'loot_entities.txt',
    'loot_chests.txt',
    'loot_blocks.txt',
    'biome_spawns.txt',
    'structure_biomes.txt',
    'entity_loottables.txt',
    'mod_meta.txt',
    'lang_names.txt',
]


def check(lists_dir: Path) -> dict:
    warnings = []
    info     = []
    ok       = []

    for filename in EXPECTED_FILES:
        path = lists_dir / filename
        if not path.exists():
            warnings.append(f'{filename} is MISSING')
            continue
        line_count = sum(
            1 for line in path.read_text(encoding='utf-8', errors='ignore').splitlines()
            if line.strip() and not line.startswith('=') and not line.startswith('-')
        )
        ok.append(f'{filename}  ({line_count} entries)')

    return {'warnings': warnings, 'info': info, 'ok': ok}
