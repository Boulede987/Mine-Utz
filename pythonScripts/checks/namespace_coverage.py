"""
checks/namespace_coverage.py
=============================
Checks that all namespaces found in registries have corresponding mod metadata.
"""

from lib.normalise import namespaces_of


def check(
    all_items: set,
    mobs: set,
    biomes: set,
    mod_ids: set,
) -> dict:
    warnings = []
    info     = []
    ok       = []

    item_namespace  = namespaces_of(all_items)
    mob_namespace   = namespaces_of(mobs)
    biome_namespace = namespaces_of(biomes)
    all_namespaces  = item_namespace | mob_namespace | biome_namespace

    # Namespaces in registries but not in mod metadata
    missing_meta = sorted(all_namespaces - mod_ids - {'minecraft'})
    if missing_meta:
        warnings.append((
            f'{len(missing_meta)} namespaces found in registries but not in mod_meta.txt',
            missing_meta
        ))
    else:
        ok.append('All namespaces have mod metadata')

    # Namespaces with entities but no items (library/utility mods)
    entity_only = sorted(mob_namespace - item_namespace)
    if entity_only:
        warnings.append((
            f'{len(entity_only)} namespaces have entities but no items',
            entity_only
        ))
    else:
        ok.append('All entity namespaces also have items')

    # Namespaces with biomes but no items (worldgen libraries)
    biome_only = sorted(biome_namespace - item_namespace)
    if biome_only:
        warnings.append((
            f'{len(biome_only)} namespaces have biomes but no items',
            biome_only
        ))
    else:
        ok.append('All biome namespaces also have items')

    return {'warnings': warnings, 'info': info, 'ok': ok}
