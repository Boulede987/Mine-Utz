"""
sql_gen/08_mc_recipes.py
=========================
Generates SQL inserts for the recipe system — vanilla Minecraft 1.21.1.

Source : lists/recipes.txt
Format : recipe_id | type | station | result_item | xCount | ingredients (CSV)

Filter : only minecraft: recipe IDs (i.e. vanilla recipes)

Tables
------
  Recipe           — (namespace, name, type)
  RecipeOutput     — (recipe_ns, recipe_name, item_ns, item_name, count)
  RecipeIngredient — (recipe_ns, recipe_name, item_ns, item_name, quantity=1)
                     Tag ingredients (#...) and non-minecraft items are skipped;
                     their Item rows don't exist yet.
  RecipeStation    — (recipe_ns, recipe_name, station_ns, station_name)

Output: BDD/Scripts/inserts/08_mc_recipes.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/08_mc_recipes.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_pipe_file, load_registry

OUTPUT = SQL_INSERTS / '08_mc_recipes.sql'


def _parse_count(field: str) -> int:
    """Parse 'x3' -> 3."""
    stripped = field.strip().lstrip('x')
    return int(stripped) if stripped.isdigit() else 1


def _load_valid_mc_items() -> set[str]:
    return {
        entry for entry in load_registry(LISTS, 'items.txt') | load_registry(LISTS, 'blocks.txt')
        if entry.startswith('minecraft:')
    }


def _parse_all_recipe_rows(mc_items: set[str]) -> tuple[list, list, list, list]:
    recipe_rows:      list[tuple] = []
    output_rows:      list[tuple] = []
    ingredient_rows:  list[tuple] = []
    station_rows:     list[tuple] = []

    seen_recipes:     set[tuple] = set()
    seen_outputs:     set[tuple] = set()
    seen_ingredients: set[tuple] = set()
    seen_stations:    set[tuple] = set()

    for line in load_pipe_file(LISTS, 'recipes.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 6:
            continue
        recipe_id, recipe_type, station, result, count_str, ingredients_str = (
            parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]
        )
        if not recipe_id.startswith('minecraft:'):
            continue
        rec_ns, rec_name = recipe_id.split(':', 1)

        # Deduplicate by (namespace, name) — extractor emits one line per output
        rec_key = (rec_ns, rec_name)
        if rec_key not in seen_recipes:
            seen_recipes.add(rec_key)
            recipe_rows.append((rec_ns, rec_name, recipe_type))

        if result.startswith('minecraft:'):
            res_ns, res_name = result.split(':', 1)
            count = _parse_count(count_str)
            key = (rec_ns, rec_name, res_ns, res_name)
            if key not in seen_outputs:
                seen_outputs.add(key)
                output_rows.append((rec_ns, rec_name, res_ns, res_name, count))

        if station.startswith('minecraft:'):
            sta_ns, sta_name = station.split(':', 1)
            key = (rec_ns, rec_name, sta_ns, sta_name)
            if key not in seen_stations:
                seen_stations.add(key)
                station_rows.append((rec_ns, rec_name, sta_ns, sta_name))

        for raw in ingredients_str.split(','):
            ingredient = raw.strip()
            if not ingredient or ingredient.startswith('#') or ingredient not in mc_items:
                continue
            ing_ns, ing_name = ingredient.split(':', 1)
            key = (rec_ns, rec_name, ing_ns, ing_name)
            if key not in seen_ingredients:
                seen_ingredients.add(key)
                ingredient_rows.append((rec_ns, rec_name, ing_ns, ing_name, 1))

    print(f'Recipes            : {len(recipe_rows)}')
    print(f'RecipeOutput       : {len(output_rows)}')
    print(f'RecipeStation      : {len(station_rows)}')
    print(f'RecipeIngredient   : {len(ingredient_rows)}')
    return recipe_rows, output_rows, station_rows, ingredient_rows


def main() -> None:
    mc_items = _load_valid_mc_items()
    recipe_rows, output_rows, station_rows, ingredient_rows = _parse_all_recipe_rows(mc_items)

    write_sql(OUTPUT, '08 - Minecraft recipes', [
        ('Recipe — vanilla crafting, smelting, stonecutting, smithing',
         insert_rows('Recipe', ['namespace', 'name', 'type'], recipe_rows)),

        ('RecipeOutput — what each recipe produces',
         insert_rows(
             'RecipeOutput',
             ['recipe_namespace', 'recipe_name', 'item_namespace', 'item_name', 'count'],
             output_rows,
         )),

        ('RecipeStation — which crafting station the recipe uses',
         insert_rows(
             'RecipeStation',
             ['recipe_namespace', 'recipe_name', 'station_namespace', 'station_name'],
             station_rows,
         )),

        ('RecipeIngredient — input items (tags and non-vanilla items skipped)',
         insert_rows(
             'RecipeIngredient',
             ['recipe_namespace', 'recipe_name', 'item_namespace', 'item_name', 'quantity'],
             ingredient_rows,
         )),
    ])


main()
