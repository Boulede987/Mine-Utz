"""
check_consistency.py
====================
Cross-references all extracted .txt files in lists/ and reports
inconsistencies that could cause problems during DB import.

Run from project root:
    python pythonScripts/check_consistency.py

Output: printed to console + lists/consistency_report.txt
"""

from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).parent.parent
LISTS = ROOT / 'lists'

# ── Helpers ───────────────────────────────────────────────────────────────────

def load_registry(filename):
    """
    Load a registry dump. Handles two formats:

    Format A (extract_from_log.py) - no block markers, just sections:
      --- namespace ---
      namespace:entry

    Format B (extract_from_jars.py) - block markers:
      ===== LABEL DUMP START =====
      --- namespace ---
      namespace:entry
      ===== LABEL DUMP END (N total) =====
    """
    path = LISTS / filename
    if not path.exists():
        print(f'  WARNING: {filename} not found')
        return set()

    entries  = set()
    has_markers = False
    in_block = False

    lines = path.read_text(encoding='utf-8', errors='ignore').splitlines()

    # Detect which format the file uses
    for line in lines:
        if 'DUMP START' in line or ('=====' in line and 'START' in line):
            has_markers = True
            break

    for raw_line in lines:
        line = raw_line.strip()
        if not line:
            continue

        if has_markers:
            if '=====' in line and ('START' in line):
                in_block = True
                continue
            if '=====' in line and ('END' in line or 'total' in line):
                in_block = False
                continue
            if not in_block:
                continue

        # Skip section headers and non-registry lines
        if line.startswith('---'):
            continue
        if line.startswith('====='):
            continue

        # Only keep lines that look like registry names
        if ':' in line and ' ' not in line:
            entries.add(line)

    return entries

def load_pipe_file(filename, col=None):
    """
    Load a pipe-separated file. Handles two formats:

    Format A (no markers) - pipe rows start immediately:
      source_id | item | min:1 | max:1

    Format B (block markers):
      ===== HEADER =====
      source_id | item | min:1 | max:1
      ===== END (N total) =====
    """
    path = LISTS / filename
    if not path.exists():
        return set() if col is not None else []

    lines = path.read_text(encoding='utf-8', errors='ignore').splitlines()
    results  = set() if col is not None else []
    in_block = False

    # Detect format
    has_markers = any('=====' in l and ('START' in l or 'END' in l or 'format' in l.lower()) for l in lines)

    for raw_line in lines:
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith('====='):
            if has_markers:
                in_block = 'END' not in line and 'total' not in line
            continue
        if has_markers and not in_block:
            continue
        if line.startswith('---'):
            continue
        # Must be a pipe-separated row
        if '|' not in line:
            continue
        parts = [p.strip() for p in line.split('|')]
        if col is not None:
            if col < len(parts):
                results.add(parts[col])
        else:
            results.append(line)
    return results

def namespace_of(registry_name):
    return registry_name.split(':')[0] if ':' in registry_name else 'unknown'

def namespaces_of(registry_set):
    return {namespace_of(r) for r in registry_set}

# ── Load all files ────────────────────────────────────────────────────────────

print('Loading files...')

items      = load_registry('items.txt')
blocks     = load_registry('blocks.txt')
mobs       = load_registry('mobs.txt')
biomes     = load_registry('biomes.txt')
structures = load_registry('structures.txt')

# Combine items and blocks into one item universe
all_items = items | blocks

# Recipes: col 0=id, col 2=station, col 3=result, col 5=ingredients (comma-sep)
recipe_lines     = load_pipe_file('recipes.txt')
recipe_results   = set()
recipe_stations  = set()
recipe_inputs    = set()
for line in recipe_lines:
    parts = [p.strip() for p in line.split('|')]
    if len(parts) < 5:
        continue
    recipe_results.add(parts[3])
    recipe_stations.add(parts[2])
    for ing in parts[5].split(',') if len(parts) > 5 else []:
        ing = ing.strip()
        if ing:
            recipe_inputs.add(ing)

# Loot tables
loot_entity_sources  = load_pipe_file('loot_entities.txt',  col=0)
loot_chest_sources   = load_pipe_file('loot_chests.txt',    col=0)
loot_block_sources   = load_pipe_file('loot_blocks.txt',    col=0)
loot_entity_items    = load_pipe_file('loot_entities.txt',  col=1)
loot_chest_items     = load_pipe_file('loot_chests.txt',    col=1)
loot_block_items     = load_pipe_file('loot_blocks.txt',    col=1)
all_loot_items       = {
    i for i in (loot_entity_items | loot_chest_items | loot_block_items)
    if '/' not in i   # skip loot table path references misread as item IDs
}

# Entity loot table assignments
entity_loot_entities = load_pipe_file('entity_loottables.txt', col=0)
entity_loot_tables   = load_pipe_file('entity_loottables.txt', col=1)

# Biome spawns
spawn_biomes   = load_pipe_file('biome_spawns.txt', col=0)
spawn_entities = {
    e if (':' in e or e.startswith('#')) else f'minecraft:{e}'
    for e in load_pipe_file('biome_spawns.txt', col=1)
}

# Structure biomes
struct_biome_structures = load_pipe_file('structure_biomes.txt', col=0)
struct_biome_biomes     = {
    b if (':' in b or b.startswith('#')) else f'minecraft:{b}'
    for b in load_pipe_file('structure_biomes.txt', col=1)
}

# Mod metadata
mod_ids = load_pipe_file('mod_meta.txt', col=0)

# Lang names
lang_keys = load_pipe_file('lang_names.txt', col=0)

# ── Checks ────────────────────────────────────────────────────────────────────

report = []
total_issues = 0
total_info   = 0

def section(title):
    report.append(f'\n{"="*60}')
    report.append(f'  {title}')
    report.append(f'{"="*60}')

def warn(msg, items_list=None, limit=20):
    global total_issues
    report.append(f'  WARN: {msg}')
    total_issues += 1
    if items_list:
        for i in sorted(items_list)[:limit]:
            report.append(f'    - {i}')
        if len(items_list) > limit:
            report.append(f'    ... and {len(items_list) - limit} more')

def info(msg, items_list=None, limit=10):
    global total_info
    report.append(f'  INFO: {msg}')
    total_info += 1
    if items_list:
        for i in sorted(items_list)[:limit]:
            report.append(f'    - {i}')
        if len(items_list) > limit:
            report.append(f'    ... and {len(items_list) - limit} more')

def ok(msg):
    report.append(f'  OK  : {msg}')

# ── 1. File presence ──────────────────────────────────────────────────────────

section('FILE PRESENCE')
expected_files = [
    'items.txt', 'blocks.txt', 'mobs.txt', 'biomes.txt', 'structures.txt',
    'recipes.txt', 'loot_entities.txt', 'loot_chests.txt', 'loot_blocks.txt',
    'biome_spawns.txt', 'structure_biomes.txt', 'entity_loottables.txt',
    'mod_meta.txt', 'lang_names.txt',
]
for f in expected_files:
    p = LISTS / f
    if p.exists():
        count = sum(1 for l in p.read_text(encoding='utf-8').splitlines()
                    if l.strip() and not l.startswith('=') and not l.startswith('-'))
        ok(f'{f}  ({count} entries)')
    else:
        warn(f'{f} is MISSING')

# ── 2. Namespace coverage ─────────────────────────────────────────────────────

section('NAMESPACE COVERAGE')
item_ns     = namespaces_of(all_items)
mob_ns      = namespaces_of(mobs)
biome_ns    = namespaces_of(biomes)
struct_ns   = namespaces_of(structures)
mod_ns      = set(mod_ids)

# Namespaces present in registries but missing from mod_meta
all_ns = item_ns | mob_ns | biome_ns | struct_ns
missing_meta = all_ns - mod_ns - {'minecraft'}
if missing_meta:
    warn(f'{len(missing_meta)} namespaces found in registries but not in mod_meta.txt', missing_meta)
else:
    ok('All namespaces have mod metadata')

# Namespaces with entities but no items
entity_only_ns = mob_ns - item_ns
if entity_only_ns:
    warn(f'{len(entity_only_ns)} namespaces have entities but no items', entity_only_ns)
else:
    ok('All entity namespaces also have items')

# Namespaces with biomes but no items
biome_only_ns = biome_ns - item_ns
if biome_only_ns:
    warn(f'{len(biome_only_ns)} namespaces have biomes but no items', biome_only_ns)
else:
    ok('All biome namespaces also have items')

# ── 3. Recipe integrity ───────────────────────────────────────────────────────

section('RECIPE INTEGRITY')

# Result items not in item registry
unknown_results = {r for r in recipe_results if r and r not in all_items}
if unknown_results:
    warn(f'{len(unknown_results)} recipe result items not found in item registry', unknown_results)
else:
    ok('All recipe result items are in the item registry')

# Input items not in item registry (tags like #minecraft:logs are expected)
unknown_inputs = {i for i in recipe_inputs
                  if i and not i.startswith('#') and i not in all_items}
if unknown_inputs:
    warn(f'{len(unknown_inputs)} recipe ingredient items not found in item registry', unknown_inputs)
else:
    ok('All recipe ingredient items are in the item registry')

# Stations not in item registry
unknown_stations = {s for s in recipe_stations
                    if s and not s.startswith('minecraft:') and s not in all_items}
if unknown_stations:
    info(f'{len(unknown_stations)} mod stations not found in item registry (may be custom machine types)',
         unknown_stations)
else:
    ok('All recipe stations are in the item registry')

# Items that appear in no recipe at all
no_recipe_items = all_items - recipe_results - recipe_inputs
info(f'{len(no_recipe_items)} items appear in no recipe (as input or output) — expected for loot-only items')

# ── 4. Loot table integrity ───────────────────────────────────────────────────

section('LOOT TABLE INTEGRITY')

# Loot items not in item registry
unknown_loot = {i for i in all_loot_items if i and i not in all_items}
if unknown_loot:
    warn(f'{len(unknown_loot)} loot table items not found in item registry', unknown_loot)
else:
    ok('All loot table items are in the item registry')

# Entities that have an assigned loot table but no loot file was found
assigned_but_no_file = {
    e for e in entity_loot_entities
    if not any(s.startswith(namespace_of(e) + ':') for s in loot_entity_sources)
}
if assigned_but_no_file:
    info(f'{len(assigned_but_no_file)} entities have an assigned loot table but no loot file found in jars',
         assigned_but_no_file)
else:
    ok('All entities with assigned loot tables have corresponding loot files')

# Entity loot table IDs pointing to loot files we have
matched = entity_loot_tables & loot_entity_sources
info(f'{len(matched)} / {len(entity_loot_tables)} entity loot table assignments matched to extracted loot files')

# ── 5. Biome spawn integrity ──────────────────────────────────────────────────

section('BIOME SPAWN INTEGRITY')

unknown_spawn_biomes = spawn_biomes - biomes
if unknown_spawn_biomes:
    warn(f'{len(unknown_spawn_biomes)} biomes in spawn table not found in biome registry', unknown_spawn_biomes)
else:
    ok('All spawn table biomes are in the biome registry')

unknown_spawn_entities = spawn_entities - mobs
if unknown_spawn_entities:
    warn(f'{len(unknown_spawn_entities)} entities in spawn table not found in entity registry', unknown_spawn_entities)
else:
    ok('All spawn table entities are in the entity registry')

# ── 6. Structure-biome integrity ─────────────────────────────────────────────

section('STRUCTURE-BIOME INTEGRITY')

# Tags (starting with #) are valid and can't be cross-referenced
struct_biome_biomes_plain = {b for b in struct_biome_biomes if not b.startswith('#')}
struct_biome_biomes_tags  = {b for b in struct_biome_biomes if b.startswith('#')}

unknown_struct_biomes = struct_biome_biomes_plain - biomes
if unknown_struct_biomes:
    warn(f'{len(unknown_struct_biomes)} biomes in structure-biome table not in biome registry',
         unknown_struct_biomes)
else:
    ok('All plain biome references in structure-biome table are valid')

info(f'{len(struct_biome_biomes_tags)} structure-biome entries use biome tags (cannot cross-reference)')

unknown_structs = struct_biome_structures - structures
if unknown_structs:
    warn(f'{len(unknown_structs)} structures in structure-biome table not found in structure registry',
         unknown_structs)
else:
    ok('All structures in structure-biome table are in the structure registry')

# ── 7. Lang name coverage ─────────────────────────────────────────────────────

section('LANG NAME COVERAGE')

# Check what fraction of items/mobs/biomes have a lang entry
def lang_key_for_item(r):
    # e.g. minecraft:stone -> item.minecraft.stone or block.minecraft.stone
    ns, name = r.split(':', 1) if ':' in r else ('minecraft', r)
    name = name.replace('/', '.')
    return {f'item.{ns}.{name}', f'block.{ns}.{name}'}

items_with_lang  = sum(1 for i in all_items  if lang_key_for_item(i) & lang_keys)
mobs_with_lang   = sum(1 for m in mobs       if f'entity.{m.replace(":", ".").replace("/", ".")}' in lang_keys)

info(f'{items_with_lang} / {len(all_items)} items have a lang entry')
info(f'{mobs_with_lang} / {len(mobs)} mobs have a lang entry')

# ── Summary ───────────────────────────────────────────────────────────────────

report.insert(0, f'Consistency Report')
report.insert(1, f'==================')
report.insert(2, f'Warnings : {total_issues}')
report.insert(3, f'Info     : {total_info}')

output = '\n'.join(report)
print(output)

dest = LISTS / 'consistency_report.txt'
dest.write_text(output, encoding='utf-8')
print(f'\nWritten to {dest}')