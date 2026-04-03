"""
debug_recipes.py
================
Counts how many recipe JSON files exist across all jars,
how many are skipped and why, and shows samples of each skip reason.

Run from project root:
    python pythonScripts/debug_recipes.py

Output: printed to console + lists/debug/recipe_skip_report.txt
"""

import json
import re
import zipfile
from pathlib import Path
from collections import defaultdict

ROOT     = Path(__file__).parent.parent
MODS_DIR = ROOT / 'minecraft' / 'mods'
OUT      = ROOT / 'lists' / 'debug'
OUT.mkdir(parents=True, exist_ok=True)

SAMPLE_PER_REASON = 3   # how many example JSONs to show per skip reason

def read_json(zf, name):
    try:
        return json.loads(zf.read(name).decode('utf-8', errors='ignore'))
    except Exception as e:
        return None

# ── Scan ──────────────────────────────────────────────────────────────────────

total_files  = 0
parsed_ok    = 0
skipped      = defaultdict(list)   # reason -> [(jar, path, data_snippet)]

jars = sorted(MODS_DIR.glob('*.jar'))
print(f'Scanning {len(jars)} jars...\n')

for jar_path in jars:
    try:
        with zipfile.ZipFile(jar_path, 'r') as zf:
            for name in zf.namelist():
                if not name.startswith('data/') or not name.endswith('.json'):
                    continue
                parts = name.split('/', 2)
                if len(parts) < 3:
                    continue
                rel = parts[2]
                if not rel.startswith('recipes/'):
                    continue

                total_files += 1
                data = read_json(zf, name)

                if not isinstance(data, dict):
                    skipped['bad_json'].append((jar_path.name, name, str(data)[:120]))
                    continue

                rtype = data.get('type', '')
                if not rtype:
                    skipped['no_type'].append((jar_path.name, name, json.dumps(data, indent=2)[:300]))
                    continue

                # Check result field
                result_raw = data.get('result') or data.get('output') or data.get('results')
                if result_raw is None:
                    skipped['no_result'].append((jar_path.name, name, json.dumps(data, indent=2)[:300]))
                    continue

                # Check result resolves to something
                def parse_result(r):
                    if isinstance(r, str): return [r]
                    if isinstance(r, dict):
                        return [r.get('id') or r.get('item', '')]
                    if isinstance(r, list):
                        out = []
                        for e in r:
                            if isinstance(e, dict):
                                out.append(e.get('id') or e.get('item', ''))
                            elif isinstance(e, str):
                                out.append(e)
                        return out
                    return []

                ids = [i for i in parse_result(result_raw) if i]
                if not ids:
                    skipped['empty_result_id'].append((jar_path.name, name, json.dumps(data, indent=2)[:300]))
                    continue

                parsed_ok += 1

    except zipfile.BadZipFile:
        pass
    except Exception as e:
        print(f'  !! {jar_path.name}: {e}')

# ── Report ────────────────────────────────────────────────────────────────────

lines = []
lines.append(f'Total recipe JSON files found : {total_files}')
lines.append(f'Successfully parsed           : {parsed_ok}')
lines.append(f'Skipped                       : {total_files - parsed_ok}')
lines.append('')

for reason, examples in skipped.items():
    lines.append(f'=== SKIP REASON: {reason}  ({len(examples)} files) ===')
    for jar, path, snippet in examples[:SAMPLE_PER_REASON]:
        lines.append(f'  JAR : {jar}')
        lines.append(f'  PATH: {path}')
        lines.append(f'  DATA: {snippet}')
        lines.append('')

report = '\n'.join(lines)
print(report)

dest = OUT / 'recipe_skip_report.txt'
dest.write_text(report, encoding='utf-8')
print(f'\nWritten to {dest}')