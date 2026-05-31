"""
collectors/recipes.py
=====================
Collects recipe data from mod jar JSON files.
"""

STATION_MAP = {
    'crafting_shaped':    'minecraft:crafting_table',
    'crafting_shapeless': 'minecraft:crafting_table',
    'smelting':           'minecraft:furnace',
    'blasting':           'minecraft:blast_furnace',
    'smoking':            'minecraft:smoker',
    'campfire_cooking':   'minecraft:campfire',
    'stonecutting':       'minecraft:stonecutter',
    'smithing_transform': 'minecraft:smithing_table',
    'smithing_trim':      'minecraft:smithing_table',
}


def _unwrap_conditional(data: dict) -> dict:
    """
    Unwrap neoforge:conditional wrappers.
    These nest the real recipe inside recipes[].recipe.
    """
    if data.get('type') == 'neoforge:conditional':
        for entry in data.get('recipes', []):
            inner = entry.get('recipe')
            if isinstance(inner, dict) and inner.get('type'):
                return inner
    return data


def _strip_quantity(value: str) -> str:
    """
    Strip a leading quantity prefix from a string ingredient.
    e.g. '144 minecraft:iron_ingot' -> 'minecraft:iron_ingot'
         '#c:plates/copper'         -> '#c:plates/copper'
    """
    parts = value.split(' ', 1)
    if len(parts) == 2 and parts[0].isdigit():
        return parts[1]
    return value


def _collect_ingredient(value, inputs: list) -> None:
    if isinstance(value, dict):
        inputs.append(value.get('item') or value.get('tag') or '')
    elif isinstance(value, str):
        inputs.append(_strip_quantity(value))


def _parse_inputs(data: dict) -> list[str]:
    """Extract all ingredient item IDs from a recipe dict."""
    inputs = []

    for key in ('ingredients', 'ingredient', 'input', 'inputs', 'base', 'addition'):
        value = data.get(key)
        if value is None:
            continue
        if isinstance(value, list):
            for item in value:
                _collect_ingredient(item, inputs)
        else:
            _collect_ingredient(value, inputs)

    for value in data.get('key', {}).values():
        _collect_ingredient(value, inputs)

    return [item for item in inputs if item]


def _parse_results(result_raw) -> list[tuple[str, int]]:
    """
    Resolve the result field to a list of (item_id, count) tuples.
    Handles strings, single dicts, and lists (e.g. Thermal machines).
    """
    if isinstance(result_raw, str):
        return [(result_raw, 1)]

    if isinstance(result_raw, dict):
        item_id = result_raw.get('item') or result_raw.get('id', '')
        if not item_id:
            return []
        count = result_raw.get('count') or result_raw.get('chance') or 1
        return [(item_id, max(1, int(float(count))))]

    if isinstance(result_raw, list):
        results = []
        for entry in result_raw:
            if isinstance(entry, str):
                results.append((entry, 1))
            elif isinstance(entry, dict):
                item_id = entry.get('item') or entry.get('id', '')
                if not item_id:
                    continue
                count = entry.get('count') or entry.get('chance') or 1
                results.append((item_id, max(1, int(float(count)))))
        return results

    return []


def make_recipe_collector(output_rows: list) -> callable:
    """
    Returns a collect_recipe(namespace, relative_path, data) function
    that appends pipe-separated rows to output_rows.
    """
    def collect_recipe(namespace: str, relative_path: str, data: dict) -> None:
        data = _unwrap_conditional(data)

        recipe_type = data.get('type', '')
        result_raw  = data.get('result') or data.get('output') or data.get('results')
        results     = _parse_results(result_raw)
        if not results:
            return

        short_type  = recipe_type.split(':')[-1]
        station     = STATION_MAP.get(short_type, recipe_type)
        recipe_id   = f"{namespace}:{relative_path.replace('/', '.').removesuffix('.json')}"
        inputs_str  = ', '.join(_parse_inputs(data))

        for item_id, count in results:
            output_rows.append(
                f"{recipe_id} | {recipe_type} | {station} | {item_id} | x{count} | {inputs_str}"
            )

    return collect_recipe