"""
checks/loot_integrity.py
========================
Validates loot table data against the item and entity registries.
"""


def check(
    loot_entity_items: set,
    loot_chest_items: set,
    loot_block_items: set,
    entity_loot_entities: set,
    entity_loot_tables: set,
    loot_entity_sources: set,
    all_items: set,
) -> dict:
    warnings = []
    info     = []
    ok       = []

    all_loot_items = loot_entity_items | loot_chest_items | loot_block_items

    # Loot items not in item registry
    # Items with '/' in their ID are loot table path references misread as items — filter them out
    unknown_loot = sorted(
        item for item in all_loot_items
        if item and item not in all_items and '/' not in item
    )
    dynamic_loot = sorted(item for item in all_loot_items if item and '/' in item)

    if unknown_loot:
        warnings.append((
            f'{len(unknown_loot)} loot table items not found in item registry',
            unknown_loot
        ))
    else:
        ok.append('All loot table items are in the item registry')

    if dynamic_loot:
        info.append((
            f'{len(dynamic_loot)} loot entries have path-like IDs (dynamic registry items,'
            ' e.g. Cobblemon/Bibliocraft — cannot be cross-referenced statically)',
            dynamic_loot[:10]  # show sample only
        ))

    # Entities with assigned loot table but no extracted loot file
    assigned_without_file = sorted(
        entity for entity in entity_loot_entities
        if not any(source.startswith(entity.split(':')[0] + ':') for source in loot_entity_sources)
    )
    if assigned_without_file:
        info.append((
            f'{len(assigned_without_file)} entities have an assigned loot table'
            ' but no loot file found in jars'
            ' (projectiles, falling blocks, etc.)',
            assigned_without_file
        ))
    else:
        ok.append('All entities with assigned loot tables have corresponding loot files')

    # Match rate
    matched = len(entity_loot_tables & loot_entity_sources)
    total   = len(entity_loot_tables)
    info.append((
        f'{matched} / {total} entity loot table assignments matched to extracted loot files',
        []
    ))

    return {'warnings': warnings, 'info': info, 'ok': ok}
