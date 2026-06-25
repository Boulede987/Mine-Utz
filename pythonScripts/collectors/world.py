"""
collectors/world.py
===================
Collects biome and structure data from mod jar JSON files.
"""


def make_biome_collector(
    spawn_rows: list,
    feature_rows: list,
    stat_rows: list | None = None,
    entity_cat_dict: dict | None = None,
) -> callable:
    """
    Returns a collect_biome(namespace, relative_path, data) function.

    stat_rows      — if provided, appends "biome_id | temperature | #RRGGBB"
    entity_cat_dict — if provided, maps entity_id -> primary spawn category
                      (monster wins over other categories)
    """
    def collect_biome(namespace: str, relative_path: str, data: dict) -> None:
        biome_id = f"{namespace}:{relative_path.removesuffix('.json')}"

        spawners = data.get('spawners', {})
        if isinstance(spawners, dict):
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
                    if entity_cat_dict is not None:
                        # monster category takes priority over all others
                        if category == 'monster' or entity not in entity_cat_dict:
                            entity_cat_dict[entity] = category

        if stat_rows is not None:
            temp = data.get('temperature')
            fog  = data.get('effects', {}).get('fog_color')
            if temp is not None and fog is not None:
                fog_hex = f'#{int(fog) & 0xFFFFFF:06X}'
                stat_rows.append(f"{biome_id} | {temp} | {fog_hex}")

        for stage in data.get('features', []):
            if isinstance(stage, list):
                for feature in stage:
                    if isinstance(feature, str):
                        feature_rows.append(f"{biome_id} | {feature}")

    return collect_biome


def make_structure_collector(
    structure_biome_rows: list,
    structure_spawn_rows: list | None = None,
) -> tuple[callable, callable]:
    """
    Returns (collect_structure, collect_structure_set) functions
    that append to structure_biome_rows and optionally structure_spawn_rows.
    """
    def _add_biome(structure_id: str, biome_value) -> None:
        if isinstance(biome_value, str) and biome_value:
            structure_biome_rows.append(f"{structure_id} | {biome_value}")
        elif isinstance(biome_value, list):
            for biome in biome_value:
                if biome:
                    structure_biome_rows.append(f"{structure_id} | {biome}")

    def collect_structure(namespace: str, relative_path: str, data: dict) -> None:
        structure_id = f"{namespace}:{relative_path.removesuffix('.json')}"
        _add_biome(structure_id, data.get('biomes', ''))

        if structure_spawn_rows is not None:
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

    def collect_structure_set(namespace: str, relative_path: str, data: dict) -> None:
        for entry in data.get('structures', []):
            structure_id = entry.get('structure', '')
            if not structure_id:
                continue
            placement = data.get('placement', {})
            allowed   = placement.get('allowed_biomes') or placement.get('biomes', '')
            _add_biome(structure_id, allowed)

    return collect_structure, collect_structure_set
