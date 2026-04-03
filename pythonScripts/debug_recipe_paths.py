"""
debug_recipe_paths.py
=====================
Finds every unique top-level data path pattern across all jars,
so we can see where recipes (and other data) actually live.

Run from project root:
    python pythonScripts/debug_recipe_paths.py

Output: lists/debug/all_data_paths.txt
"""

import zipfile
from pathlib import Path
from collections import defaultdict

ROOT     = Path(__file__).parent.parent
MODS_DIR = ROOT / 'minecraft' / 'mods'
OUT      = ROOT / 'lists' / 'debug'
OUT.mkdir(parents=True, exist_ok=True)

# Collect: namespace -> set of top-level subfolder names under data/<namespace>/
path_counts  = defaultdict(int)   # "data/<ns>/<subfolder>" -> file count
recipe_paths = []                 # every path that contains "recipe" anywhere

jars = sorted(MODS_DIR.glob('*.jar'))
print(f'Scanning {len(jars)} jars...')

for jar_path in jars:
    try:
        with zipfile.ZipFile(jar_path, 'r') as zf:
            for name in zf.namelist():
                if not name.startswith('data/') or not name.endswith('.json'):
                    continue
                parts = name.split('/')
                if len(parts) < 4:
                    continue
                # key = data / namespace / subfolder
                key = '/'.join(parts[:3])
                path_counts[key] += 1

                if 'recipe' in name.lower():
                    recipe_paths.append(f"{jar_path.name}  ::  {name}")

    except (zipfile.BadZipFile, Exception):
        pass

# ── Write ─────────────────────────────────────────────────────────────────────

lines_all = ['=== ALL DATA SUBFOLDERS (namespace/subfolder : file count) ===', '']
for key in sorted(path_counts):
    lines_all.append(f"{key}  ({path_counts[key]} files)")

lines_all += ['', '=== ALL PATHS CONTAINING "recipe" ===', '']
lines_all += sorted(recipe_paths)

dest = OUT / 'all_data_paths.txt'
dest.write_text('\n'.join(lines_all), encoding='utf-8')

print(f'Found {len(path_counts)} unique data subfolders')
print(f'Found {len(recipe_paths)} recipe-containing paths')
print(f'Written to {dest}')
print('\nTop 40 subfolders by file count:')
for key, count in sorted(path_counts.items(), key=lambda x: -x[1])[:40]:
    print(f'  {count:5d}  {key}')