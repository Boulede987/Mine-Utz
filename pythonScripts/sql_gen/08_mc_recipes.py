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
from lib.paths import LISTS, ROOT
from lib.loaders import load_pipe_file, load_registry

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '08_mc_recipes.sql'

# Valid item set — vanilla only, guards RecipeIngredient FK references.
# Restricted to minecraft: so mod items (not yet in Item table) are excluded.
MC_ITEMS = {
    e for e in load_registry(LISTS, 'items.txt') | load_registry(LISTS, 'blocks.txt')
    if e.startswith('minecraft:')
}


def parse_count(field: str) -> int:
    """Parse 'x3' -> 3."""
    s = field.strip().lstrip('x')
    return int(s) if s.isdigit() else 1


# ── Parse recipes ─────────────────────────────────────────────────────────────

recipe_rows:      list[tuple] = []
output_rows:      list[tuple] = []
ingredient_rows:  list[tuple] = []   # deduplicated per (recipe, item)
station_rows:     list[tuple] = []   # deduplicated per recipe

# Track seen keys to avoid PK conflicts
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

    # ── Recipe ────────────────────────────────────────────────────────────────
    # Deduplicate by (namespace, name) — the extractor emits one line per output,
    # so multi-output recipes produce duplicate recipe IDs in recipes.txt.
    rec_key = (rec_ns, rec_name)
    if rec_key not in seen_recipes:
        seen_recipes.add(rec_key)
        recipe_rows.append((rec_ns, rec_name, recipe_type))

    # ── RecipeOutput ──────────────────────────────────────────────────────────
    if result.startswith('minecraft:'):
        res_ns, res_name = result.split(':', 1)
        count = parse_count(count_str)
        key = (rec_ns, rec_name, res_ns, res_name)
        if key not in seen_outputs:
            seen_outputs.add(key)
            output_rows.append((rec_ns, rec_name, res_ns, res_name, count))

    # ── RecipeStation ─────────────────────────────────────────────────────────
    if station.startswith('minecraft:'):
        sta_ns, sta_name = station.split(':', 1)
        key = (rec_ns, rec_name, sta_ns, sta_name)
        if key not in seen_stations:
            seen_stations.add(key)
            station_rows.append((rec_ns, rec_name, sta_ns, sta_name))

    # ── RecipeIngredient ──────────────────────────────────────────────────────
    for raw in ingredients_str.split(','):
        ing = raw.strip()
        if not ing or ing.startswith('#'):
            continue   # skip tag references
        if ing not in MC_ITEMS:
            continue   # skip items absent from the Item table (non-vanilla or group names)
        ing_ns, ing_name = ing.split(':', 1)
        key = (rec_ns, rec_name, ing_ns, ing_name)
        if key not in seen_ingredients:
            seen_ingredients.add(key)
            ingredient_rows.append((rec_ns, rec_name, ing_ns, ing_name, 1))

print(f'Recipes            : {len(recipe_rows)}')
print(f'RecipeOutput       : {len(output_rows)}')
print(f'RecipeStation      : {len(station_rows)}')
print(f'RecipeIngredient   : {len(ingredient_rows)}')

# ── Write ─────────────────────────────────────────────────────────────────────

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
