"""
extract_from_jars.py
====================
Scans all mod jars in  minecraft/mods/
and writes .txt files to               lists/

Output files
------------
  recipes.txt          – every recipe: id | type | station | result | count | ingredients
  loot_entities.txt    – entity loot:  source | item | min | max
  loot_chests.txt      – chest loot:   source | item | min | max
  loot_blocks.txt      – block loot:   source | item | min | max
  loot_other.txt       – other loot:   source | item | min | max
  biome_spawns.txt     – entity spawns per biome: biome | entity | weight | min | max
  biome_features.txt   – placed-feature IDs per biome (coarse block/ore gen proxy)
  structure_biomes.txt – which biome-tags / biomes each structure targets
  mod_meta.txt         – mod id | name | version | license | authors | url
  lang_names.txt       – registry_key | display_name  (from en_us.json of every mod)

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_from_jars.py
"""

import json
import re
import zipfile
from pathlib import Path

# ── Paths ─────────────────────────────────────────────────────────────────────

ROOT     = Path(__file__).parent.parent          # mineutze_DEV_INSTANCE/
MODS_DIR = ROOT / 'minecraft' / 'mods'
OUT      = ROOT / 'lists'
OUT.mkdir(parents=True, exist_ok=True)

# ── Helpers ───────────────────────────────────────────────────────────────────

def iter_jars():
    return sorted(MODS_DIR.glob('*.jar'))

def read_json(zf, name):
    """Return parsed JSON or None on any error."""
    try:
        return json.loads(zf.read(name).decode('utf-8', errors='ignore'))
    except Exception:
        return None

def write_section(f, header, rows):
    """Write a titled block of lines, like the KubeJS dumps."""
    f.write(f'===== {header} =====\n')
    for row in rows:
        f.write(row + '\n')
    f.write(f'===== END ({len(rows)} total) =====\n\n')

def resolve_count(count_val):
    """
    count can be:
      1.0                          -> min:1  max:1
      {"min": 1.0, "max": 4.0}    -> min:1  max:4
      {"type": "...", "min": ...}  -> min:1  max:4
    Returns (min_int, max_int).
    """
    if isinstance(count_val, (int, float)):
        v = int(count_val)
        return v, v
    if isinstance(count_val, dict):
        mn = count_val.get('min', count_val.get('n', 1))
        mx = count_val.get('max', count_val.get('n', 1))
        return int(mn), int(mx)
    return 1, 1

# ── Collectors ────────────────────────────────────────────────────────────────

# recipe type -> crafting station
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

recipes        = []
loot_entities  = []
loot_chests    = []
loot_blocks    = []
loot_other     = []
biome_spawns   = []
biome_features = []
struct_biomes  = []
lang           = {}
mod_meta       = []

# ── Recipe parsing ────────────────────────────────────────────────────────────

def _inputs_from(data):
    inputs = []
    def add(v):
        if isinstance(v, dict):
            inputs.append(v.get('item') or v.get('tag') or '')
        elif isinstance(v, str):
            inputs.append(v)

    for key in ('ingredients', 'ingredient', 'input', 'inputs', 'base', 'addition'):
        val = data.get(key)
        if val is None:
            continue
        if isinstance(val, list):
            for v in val:
                add(v)
        else:
            add(val)

    # shaped recipe: symbol -> ingredient map
    if 'key' in data:
        for v in data['key'].values():
            add(v)

    return [i for i in inputs if i]

def _parse_result(result_raw):
    """
    Returns a list of (item_id, count) tuples.
    Handles:
      "minecraft:stone"
      {"item": "...", "count": 2}
      {"id": "...", "count": 2}
      [{"item": "...", "count": 1}, ...]
      [{"id": "...", "count": 1}, ...]
    """
    if isinstance(result_raw, str):
        return [(result_raw, 1)]

    if isinstance(result_raw, dict):
        item_id = (result_raw.get('item')
                   or result_raw.get('id')
                   or result_raw.get('stack', {}).get('item', '') if isinstance(result_raw.get('stack'), dict) else '')
        if not item_id:
            return []
        count = result_raw.get('count') or result_raw.get('chance') or 1
        return [(item_id, max(1, int(float(count))))]

    if isinstance(result_raw, list):
        out = []
        for entry in result_raw:
            if isinstance(entry, str):
                out.append((entry, 1))
            elif isinstance(entry, dict):
                item_id = (entry.get('item')
                           or entry.get('id')
                           or '')
                if not item_id:
                    continue
                count = entry.get('count') or entry.get('chance') or 1
                out.append((item_id, max(1, int(float(count)))))
        return out

    return []

def unwrap_conditional(data):
    """
    neoforge:conditional wraps the real recipe inside recipes[].recipe.
    Unwrap it and return the inner recipe dict, or the original if not wrapped.
    """
    if data.get('type') == 'neoforge:conditional':
        for entry in data.get('recipes', []):
            inner = entry.get('recipe')
            if isinstance(inner, dict) and inner.get('type'):
                return inner
    return data

def collect_recipe(namespace, rel, data):
    data = unwrap_conditional(data)
    rtype      = data.get('type', '')
    result_raw = data.get('result') or data.get('output') or data.get('results')
    results    = _parse_result(result_raw)
    if not results:
        return

    short_type = rtype.split(':')[-1]
    station    = STATION_MAP.get(short_type, rtype)
    recipe_id  = f"{namespace}:{rel.replace('/', '.').removesuffix('.json')}"
    inputs     = _inputs_from(data)
    inputs_str = ', '.join(inputs)

    # One line per output item (multi-output recipes like Thermal pulverizer)
    for result_id, result_count in results:
        recipes.append(
            f"{recipe_id} | {rtype} | {station} | {result_id} | x{result_count} | "
            + inputs_str
        )

# ── Loot table parsing ────────────────────────────────────────────────────────

# Map the JSON top-level "type" field to a bucket name
TYPE_TO_BUCKET = {
    'minecraft:entity':    'entities',
    'minecraft:chest':     'chests',
    'minecraft:block':     'blocks',
    'minecraft:gift':      'other',
    'minecraft:fishing':   'other',
    'minecraft:shearing':  'other',
    'minecraft:equipment': 'other',
    'minecraft:generic':   'other',
}

# Fallback: first folder segment after loot_table(s)/
FOLDER_TO_BUCKET = {
    'entities': 'entities',
    'chests':   'chests',
    'blocks':   'blocks',
}

def bucket_for(loot_type_field, folder_hint):
    if loot_type_field:
        b = TYPE_TO_BUCKET.get(loot_type_field.lower())
        if b:
            return b
    return FOLDER_TO_BUCKET.get(folder_hint, 'other')

def collect_loot(namespace, rel, data):
    """
    rel is everything after  loot_table(s)/
    e.g.  chests/dungeon/silver/silver_dungeon_trash.json
          entities/loyal_zombie.json
    """
    parts     = rel.split('/')
    folder    = parts[0]   # chests / entities / blocks / …
    source_id = f"{namespace}:{rel.removesuffix('.json')}"
    loot_type = data.get('type', '')

    b_name = bucket_for(loot_type, folder)
    bucket = (loot_entities if b_name == 'entities'
              else loot_chests  if b_name == 'chests'
              else loot_blocks  if b_name == 'blocks'
              else loot_other)

    for pool in data.get('pools', []):
        for entry in pool.get('entries', []):
            etype = entry.get('type', '')

            # Skip nested loot-table references (only want concrete items)
            if 'loot_table' in etype:
                continue
            if not etype.endswith('item'):
                continue

            item_id = entry.get('name', '')
            if not item_id:
                continue

            min_c = max_c = 1
            for fn in entry.get('functions', []):
                if 'set_count' in fn.get('function', ''):
                    min_c, max_c = resolve_count(fn.get('count', 1))

            bucket.append(f"{source_id} | {item_id} | min:{min_c} | max:{max_c}")

# ── Biome parsing ─────────────────────────────────────────────────────────────

def collect_biome(namespace, rel, data):
    biome_id = f"{namespace}:{rel.removesuffix('.json')}"

    spawners = data.get('spawners', {})
    if isinstance(spawners, dict):
        for entries in spawners.values():
            if not isinstance(entries, list):
                continue
            for e in entries:
                entity = e.get('type', '')
                if entity:
                    biome_spawns.append(
                        f"{biome_id} | {entity} | "
                        f"weight:{e.get('weight', 1)} | "
                        f"min:{e.get('minCount', 1)} | max:{e.get('maxCount', 1)}"
                    )

    for stage in data.get('features', []):
        if isinstance(stage, list):
            for feat in stage:
                if isinstance(feat, str):
                    biome_features.append(f"{biome_id} | {feat}")

# ── Structure -> biome mapping ────────────────────────────────────────────────

def collect_structure(namespace, rel, data):
    struct_id = f"{namespace}:{rel.removesuffix('.json')}"
    biome_val = data.get('biomes', '')
    if isinstance(biome_val, str) and biome_val:
        struct_biomes.append(f"{struct_id} | {biome_val}")
    elif isinstance(biome_val, list):
        for b in biome_val:
            struct_biomes.append(f"{struct_id} | {b}")

def collect_structure_set(namespace, rel, data):
    for entry in data.get('structures', []):
        struct_id = entry.get('structure', '')
        if struct_id:
            placement = data.get('placement', {})
            allowed   = placement.get('allowed_biomes') or placement.get('biomes', '')
            if isinstance(allowed, str) and allowed:
                struct_biomes.append(f"{struct_id} | {allowed}")
            elif isinstance(allowed, list):
                for b in allowed:
                    struct_biomes.append(f"{struct_id} | {b}")

# ── Mod metadata ──────────────────────────────────────────────────────────────

def collect_mod_meta(jar_path, zf):
    names = zf.namelist()

    if 'META-INF/mods.toml' in names:
        try:
            raw = zf.read('META-INF/mods.toml').decode('utf-8', errors='ignore')
            def toml_get(key):
                m = re.search(rf'^\s*{key}\s*=\s*["\']?(.*?)["\']?\s*$', raw, re.MULTILINE)
                return m.group(1).strip('"\'') if m else ''
            mod_id      = toml_get('modId')
            displayName = toml_get('displayName')
            version     = toml_get('version')
            license_    = toml_get('license')
            authors     = toml_get('authors')
            url         = toml_get('displayURL')
            if mod_id:
                mod_meta.append(
                    f"{mod_id} | {displayName} | v{version} | "
                    f"license:{license_} | authors:{authors} | url:{url}"
                )
                return
        except Exception:
            pass

    if 'fabric.mod.json' in names:
        try:
            data    = read_json(zf, 'fabric.mod.json')
            mod_id  = data.get('id', '')
            name    = data.get('name', '')
            version = data.get('version', '')
            license_= ', '.join(data.get('license', [])) if isinstance(data.get('license'), list) else data.get('license', '')
            authors = ', '.join(
                a if isinstance(a, str) else a.get('name', '')
                for a in data.get('authors', [])
            )
            contact = data.get('contact', {})
            sources = contact.get('sources', '') or contact.get('homepage', '')
            if mod_id:
                mod_meta.append(
                    f"{mod_id} | {name} | v{version} | "
                    f"license:{license_} | authors:{authors} | url:{sources}"
                )
        except Exception:
            pass

# ── Main scan loop ────────────────────────────────────────────────────────────

# Matches both  loot_table/  (singular) and  loot_tables/  (plural)
LOOT_RE = re.compile(r'^loot_tables?/(.+)$')

jars = iter_jars()
print(f'Found {len(jars)} jars in {MODS_DIR}\n')

for jar_path in jars:
    print(f'  Scanning {jar_path.name} ...')
    try:
        with zipfile.ZipFile(jar_path, 'r') as zf:
            collect_mod_meta(jar_path, zf)

            for name in zf.namelist():
                # Lang files
                if re.search(r'assets/[^/]+/lang/en_us\.json$', name):
                    data = read_json(zf, name)
                    if isinstance(data, dict):
                        lang.update(data)
                    continue

                if not name.startswith('data/') or not name.endswith('.json'):
                    continue

                parts = name.split('/', 2)
                if len(parts) < 3:
                    continue
                namespace, rel = parts[1], parts[2]

                data = read_json(zf, name)
                if not isinstance(data, dict):
                    continue

                if rel.startswith('recipes/') or rel.startswith('recipe/'):
                    rel_inner = rel[len('recipes/'):] if rel.startswith('recipes/') else rel[len('recipe/'):]
                    collect_recipe(namespace, rel_inner, data)
                    continue

                loot_match = LOOT_RE.match(rel)
                if loot_match:
                    collect_loot(namespace, loot_match.group(1), data)
                    continue

                if rel.startswith('worldgen/biome/'):
                    collect_biome(namespace, rel[len('worldgen/biome/'):], data)
                    continue

                if rel.startswith('worldgen/structure/'):
                    collect_structure(namespace, rel[len('worldgen/structure/'):], data)
                    continue

                if rel.startswith('worldgen/structure_set/'):
                    collect_structure_set(namespace, rel[len('worldgen/structure_set/'):], data)

    except zipfile.BadZipFile:
        print(f'    !! Skipping (not a valid zip): {jar_path.name}')
    except Exception as e:
        print(f'    !! Error on {jar_path.name}: {e}')

# ── Write outputs ─────────────────────────────────────────────────────────────

print('\nWriting output files ...')

def write_txt(filename, sections):
    path = OUT / filename
    with open(path, 'w', encoding='utf-8') as f:
        for header, rows in sections:
            write_section(f, header, sorted(set(rows)))
    total = sum(len(r) for _, r in sections)
    print(f'  Wrote {path}  ({total} entries)')

write_txt('recipes.txt', [
    ('RECIPES  format: id | type | station | result | count | ingredients', recipes),
])

write_txt('loot_entities.txt', [
    ('LOOT -- ENTITIES  format: source | item | min | max', loot_entities),
])

write_txt('loot_chests.txt', [
    ('LOOT -- CHESTS  format: source | item | min | max', loot_chests),
])

write_txt('loot_blocks.txt', [
    ('LOOT -- BLOCKS  format: source | item | min | max', loot_blocks),
])

if loot_other:
    write_txt('loot_other.txt', [
        ('LOOT -- OTHER  format: source | item | min | max', loot_other),
    ])

write_txt('biome_spawns.txt', [
    ('BIOME SPAWN TABLE  format: biome | entity | weight | min | max', biome_spawns),
])

write_txt('biome_features.txt', [
    ('BIOME FEATURES (placed-feature IDs)  format: biome | feature_id', biome_features),
])

write_txt('structure_biomes.txt', [
    ('STRUCTURE -> BIOME  format: structure | biome_or_tag', struct_biomes),
])

write_txt('mod_meta.txt', [
    ('MOD METADATA  format: mod_id | display_name | version | license | authors | url', mod_meta),
])

lang_rows = [
    f"{k} | {v}"
    for k, v in sorted(lang.items())
    if ':' in k or '.' in k
]
write_txt('lang_names.txt', [
    ('LANG DISPLAY NAMES  format: lang_key | display_name', lang_rows),
])

print(f'\nDone. All files written to {OUT}')