"""
checks/structure_biome_integrity.py
====================================
Validates structure-biome table entries against structure and biome registries.
"""

from lib.normalise import add_minecraft_prefix


def check(
    struct_biome_structures_raw: set,
    struct_biome_biomes_raw: set,
    structures: set,
    biomes: set,
) -> dict:
    warnings = []
    info     = []
    ok       = []

    # Normalise — add minecraft: prefix to bare names, preserve tags
    struct_biome_biomes = {
        add_minecraft_prefix(biome) for biome in struct_biome_biomes_raw
    }
    struct_biome_structures = {
        add_minecraft_prefix(structure) for structure in struct_biome_structures_raw
    }

    # Split tag references from plain biome names
    plain_biomes = {biome for biome in struct_biome_biomes if '#' not in biome}
    tag_biomes   = {biome for biome in struct_biome_biomes if '#' in biome}

    unknown_biomes = sorted(plain_biomes - biomes)
    if unknown_biomes:
        warnings.append((
            f'{len(unknown_biomes)} biomes in structure-biome table not in biome registry',
            unknown_biomes
        ))
    else:
        ok.append('All plain biome references in structure-biome table are valid')

    info.append((
        f'{len(tag_biomes)} structure-biome entries use biome tags (cannot cross-reference)',
        []
    ))

    unknown_structures = sorted(struct_biome_structures - structures)
    if unknown_structures:
        warnings.append((
            f'{len(unknown_structures)} structures in structure-biome table'
            ' not found in structure registry',
            unknown_structures
        ))
    else:
        ok.append('All structures in structure-biome table are in the structure registry')

    return {'warnings': warnings, 'info': info, 'ok': ok}
