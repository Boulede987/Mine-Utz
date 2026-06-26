"""
collectors/world.py
===================
Collects biome and structure data from mod jar JSON files.
"""


def _collect_spawners(
    spawners,
    biome_id: str,
    spawn_rows: list,
    entity_cat_dict: dict | None,
) -> None:
    if not isinstance(spawners, dict):
        return
    for category, entries in spawners.items():
        if not isinstance(entries, list):
            continue
        for entity_entry in entries:
            entity = entity_entry.get('type', '')
            if not entity:
                continue
            spawn_rows.append(
                f"{biome_id} | {entity} | "
                f"weight:{entity_entry.get('weight', 1)} | "
                f"min:{entity_entry.get('minCount', 1)} | "
                f"max:{entity_entry.get('maxCount', 1)}"
            )
            if entity_cat_dict is not None and (category == 'monster' or entity not in entity_cat_dict):
                entity_cat_dict[entity] = category


def _collect_biome_stats(data: dict, biome_id: str, stat_rows: list) -> None:
    temp = data.get('temperature')
    fog  = data.get('effects', {}).get('fog_color')
    if temp is not None and fog is not None:
        stat_rows.append(f"{biome_id} | {temp} | #{int(fog) & 0xFFFFFF:06X}")


def _collect_features(features, biome_id: str, feature_rows: list) -> None:
    for stage in features:
        if not isinstance(stage, list):
            continue
        for feature in stage:
            if isinstance(feature, str):
                feature_rows.append(f"{biome_id} | {feature}")


def make_biome_collector(
    spawn_rows: list,
    feature_rows: list,
    stat_rows: list | None = None,
    entity_cat_dict: dict | None = None,
) -> callable:
    def collect_biome(namespace: str, relative_path: str, data: dict) -> None:
        biome_id = f"{namespace}:{relative_path.removesuffix('.json')}"
        _collect_spawners(data.get('spawners', {}), biome_id, spawn_rows, entity_cat_dict)
        if stat_rows is not None:
            _collect_biome_stats(data, biome_id, stat_rows)
        _collect_features(data.get('features', []), biome_id, feature_rows)
    return collect_biome


def _add_biome_entry(structure_id: str, biome_value, structure_biome_rows: list) -> None:
    if isinstance(biome_value, str) and biome_value:
        structure_biome_rows.append(f"{structure_id} | {biome_value}")
    elif isinstance(biome_value, list):
        for biome in biome_value:
            if biome:
                structure_biome_rows.append(f"{structure_id} | {biome}")


def _collect_spawn_overrides(data: dict, structure_id: str, structure_spawn_rows: list) -> None:
    for override in data.get('spawn_overrides', {}).values():
        for spawn in override.get('spawns', []):
            entity = spawn.get('type', '')
            if entity:
                structure_spawn_rows.append(
                    f"{structure_id} | {entity} | "
                    f"weight:{spawn.get('weight', 1)} | "
                    f"min:{spawn.get('minCount', 1)} | "
                    f"max:{spawn.get('maxCount', 1)}"
                )


def make_structure_collector(
    structure_biome_rows: list,
    structure_spawn_rows: list | None = None,
) -> tuple[callable, callable]:
    def collect_structure(namespace: str, relative_path: str, data: dict) -> None:
        structure_id = f"{namespace}:{relative_path.removesuffix('.json')}"
        _add_biome_entry(structure_id, data.get('biomes', ''), structure_biome_rows)
        if structure_spawn_rows is not None:
            _collect_spawn_overrides(data, structure_id, structure_spawn_rows)

    def collect_structure_set(namespace: str, relative_path: str, data: dict) -> None:
        for entry in data.get('structures', []):
            structure_id = entry.get('structure', '')
            if not structure_id:
                continue
            placement = data.get('placement', {})
            allowed   = placement.get('allowed_biomes') or placement.get('biomes', '')
            _add_biome_entry(structure_id, allowed, structure_biome_rows)

    return collect_structure, collect_structure_set
