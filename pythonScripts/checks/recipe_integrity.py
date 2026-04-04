"""
checks/recipe_integrity.py
==========================
Validates recipe data against the item registry.
"""


def check(
    recipe_lines: list,
    all_items: set,
) -> dict:
    warnings = []
    info     = []
    ok       = []

    recipe_results  = set()
    recipe_stations = set()
    recipe_inputs   = set()

    for line in recipe_lines:
        parts = [part.strip() for part in line.split('|')]
        if len(parts) < 5:
            continue
        recipe_results.add(parts[3])
        recipe_stations.add(parts[2])
        if len(parts) > 5:
            for ingredient in parts[5].split(','):
                ingredient = ingredient.strip()
                if ingredient:
                    recipe_inputs.add(ingredient)

    # Result items not in item registry
    unknown_results = sorted(result for result in recipe_results if result and result not in all_items)
    if unknown_results:
        warnings.append((
            f'{len(unknown_results)} recipe result items not found in item registry'
            ' (likely optional cross-mod recipes for uninstalled mods)',
            unknown_results
        ))
    else:
        ok.append('All recipe result items are in the item registry')

    # Ingredient items not in item registry (tag references starting with # are expected)
    unknown_inputs = sorted(
        ingredient for ingredient in recipe_inputs
        if ingredient and not ingredient.startswith('#') and ingredient not in all_items
    )
    if unknown_inputs:
        warnings.append((
            f'{len(unknown_inputs)} recipe ingredient items not found in item registry',
            unknown_inputs
        ))
    else:
        ok.append('All recipe ingredient items are in the item registry')

    # Stations not in item registry
    unknown_stations = sorted(
        station for station in recipe_stations
        if station and station not in all_items
    )
    if unknown_stations:
        info.append((
            f'{len(unknown_stations)} stations not found in item registry'
            ' (may be custom machine types)',
            unknown_stations
        ))
    else:
        ok.append('All recipe stations are in the item registry')

    # Items appearing in no recipe at all
    no_recipe_count = len(all_items - recipe_results - recipe_inputs)
    info.append((
        f'{no_recipe_count} items appear in no recipe (as input or output)'
        ' — expected for loot-only items',
        []
    ))

    return {'warnings': warnings, 'info': info, 'ok': ok}
