"""
extract_noise_to_json.py
========================
Reads multi_noise biome parameter data from logs/latest.log and writes
one JSON file per namespace to worldgen_data/.

Accepts output from either:
  /dump_all_noise        — all registered parameter lists (preferred)
  /dump_overworld_noise  — overworld only (fallback)

Output structure per namespace file:
  {
    "namespace": "...",
    "dimensions": {
      "minecraft:overworld": {
        "surface":     [...],   # depth [0.0, 0.0]  — surface placement
        "underground": [...],   # depth [1.0, 1.0]  — cave layer
        "caves":       [...],   # depth [0.2, 0.9]  — dedicated cave biomes
        "deep":        [...]    # depth >= 1.1       — deep dark
      },
      "some_mod:some_dim": { ... }
    }
  }

Entries within each section are sorted alphabetically by biome ID.

Usage:
    python pythonScripts/worldgen/extract_noise_to_json.py
"""

import json
import re
from collections import defaultdict
from pathlib import Path

LOG     = Path('minecraft/logs/latest.log')
OUT_DIR = Path('worldgen_data')

EMPTY_SECTIONS = lambda: {'surface': [], 'underground': [], 'caves': [], 'deep': []}


def strip_prefix(line):
    return re.sub(r'^\[[^\]]+\]\s+\[[^\]]+\]\s+\[[^\]]+\]:\s*', '', line)


def depth_section(depth):
    d_min = depth[0] if isinstance(depth, list) else depth
    d_max = depth[1] if isinstance(depth, list) else depth
    if d_max <= 0.05:
        return 'surface'
    if d_min >= 1.05:
        return 'deep'
    if d_min >= 0.15:
        return 'caves'
    return 'underground'


log_text = LOG.read_text(encoding='utf-8', errors='replace')
lines    = [strip_prefix(l) for l in log_text.splitlines()]
clean    = '\n'.join(lines)

# ── Parse log — prefer /dump_all_noise, fall back to /dump_overworld_noise ────

all_match = re.search(
    r'===== ALL NOISE PARAMS START =====\n(.+?)\n===== NOISE PARAMS END =====',
    clean, re.DOTALL
)
# also try variant marker
if not all_match:
    all_match = re.search(
        r'===== ALL NOISE PARAMS START =====\n(.+?)\n===== ALL NOISE PARAMS END =====',
        clean, re.DOTALL
    )

ow_match = re.search(
    r'===== OVERWORLD NOISE PARAMS START =====\n(.+?)\n===== OVERWORLD NOISE PARAMS END =====',
    clean, re.DOTALL
)

if all_match:
    raw = json.loads(all_match.group(1).strip())
    # raw = { "minecraft:overworld": [...entries...], ... }
    dim_data = {dim_id: entries for dim_id, entries in raw.items()}
    total = sum(len(v) for v in dim_data.values())
    print(f'Loaded {len(dim_data)} dimension(s), {total} total entries  [/dump_all_noise]')
elif ow_match:
    raw = json.loads(ow_match.group(1).strip())
    dim_data = {'minecraft:overworld': raw['biomes']}
    print(f'Loaded {len(dim_data["minecraft:overworld"])} entries  [/dump_overworld_noise fallback]')
else:
    print('ERROR: no noise params found in log.')
    print('  Run /dump_all_noise in-game, then re-run this script.')
    raise SystemExit(1)

# ── Group by namespace → dimension → depth section ────────────────────────────

# by_ns[namespace][dim_id][section] = [entries]
by_ns = defaultdict(lambda: defaultdict(EMPTY_SECTIONS))

for dim_id, entries in dim_data.items():
    for entry in entries:
        ns      = entry['biome'].split(':')[0]
        section = depth_section(entry['parameters']['depth'])
        by_ns[ns][dim_id][section].append(entry)

# Sort each section alphabetically
for ns_dims in by_ns.values():
    for sections in ns_dims.values():
        for lst in sections.values():
            lst.sort(key=lambda e: e['biome'])

# ── Write ─────────────────────────────────────────────────────────────────────

OUT_DIR.mkdir(exist_ok=True)

for ns, dims in sorted(by_ns.items()):
    out  = {'namespace': ns, 'dimensions': {}}
    for dim_id, sections in sorted(dims.items()):
        out['dimensions'][dim_id] = sections
        counts = '  '.join(f'{k}: {len(v)}' for k, v in sections.items() if v)
        print(f'  {ns}  {dim_id}  ({counts})')

    path = OUT_DIR / f'{ns}.json'
    path.write_text(json.dumps(out, indent=2), encoding='utf-8')

print(f'\nWritten {len(by_ns)} file(s) to {OUT_DIR}/')
