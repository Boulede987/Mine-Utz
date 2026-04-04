"""
checks/biome_spawn_integrity.py
================================
Validates biome spawn table entries against biome and entity registries.
"""

from lib.normalise import add_minecraft_prefix


def check(
    spawn_biomes_raw: set,
    spawn_entities_raw: set,
    biomes: set,
    mobs: set,
) -> dict:
    warnings = []
    info     = []
    ok       = []

    spawn_biomes   = {add_minecraft_prefix(biome)  for biome  in spawn_biomes_raw}
    spawn_entities = {add_minecraft_prefix(entity) for entity in spawn_entities_raw}

    unknown_biomes = sorted(spawn_biomes - biomes)
    if unknown_biomes:
        warnings.append((
            f'{len(unknown_biomes)} biomes in spawn table not found in biome registry',
            unknown_biomes
        ))
    else:
        ok.append('All spawn table biomes are in the biome registry')

    unknown_entities = sorted(spawn_entities - mobs)
    if unknown_entities:
        warnings.append((
            f'{len(unknown_entities)} entities in spawn table not found in entity registry',
            unknown_entities
        ))
    else:
        ok.append('All spawn table entities are in the entity registry')

    return {'warnings': warnings, 'info': info, 'ok': ok}
