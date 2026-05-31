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
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths import LATEST_LOG

OUT_DIR = Path('worldgen_data')


def _empty_sections() -> dict:
    return {'surface': [], 'underground': [], 'caves': [], 'deep': []}


def _strip_log_prefix(line: str) -> str:
    return re.sub(r'^\[[^\]]+\]\s+\[[^\]]+\]\s+\[[^\]]+\]:\s*', '', line)


def depth_section(depth: list | float) -> str:
    depth_min = depth[0] if isinstance(depth, list) else depth
    depth_max = depth[1] if isinstance(depth, list) else depth
    if depth_max <= 0.05:
        return 'surface'
    if depth_min >= 1.05:
        return 'deep'
    if depth_min >= 0.15:
        return 'caves'
    return 'underground'


def _read_clean_log() -> str:
    log_text = LATEST_LOG.read_text(encoding='utf-8', errors='replace')
    lines    = [_strip_log_prefix(line) for line in log_text.splitlines()]
    return '\n'.join(lines)


def _parse_noise_data(clean: str) -> dict[str, list]:
    all_match = re.search(
        r'===== ALL NOISE PARAMS START =====\n(.+?)\n===== NOISE PARAMS END =====',
        clean, re.DOTALL
    )
    if not all_match:
        all_match = re.search(
            r'===== ALL NOISE PARAMS START =====\n(.+?)\n===== ALL NOISE PARAMS END =====',
            clean, re.DOTALL
        )

    overworld_match = re.search(
        r'===== OVERWORLD NOISE PARAMS START =====\n(.+?)\n===== OVERWORLD NOISE PARAMS END =====',
        clean, re.DOTALL
    )

    if all_match:
        raw      = json.loads(all_match.group(1).strip())
        dim_data = dict(raw.items())
        total    = sum(len(v) for v in dim_data.values())
        print(f'Loaded {len(dim_data)} dimension(s), {total} total entries  [/dump_all_noise]')
        return dim_data

    if overworld_match:
        raw      = json.loads(overworld_match.group(1).strip())
        dim_data = {'minecraft:overworld': raw['biomes']}
        print(f'Loaded {len(dim_data["minecraft:overworld"])} entries  [/dump_overworld_noise fallback]')
        return dim_data

    print('ERROR: no noise params found in log.')
    print('  Run /dump_all_noise in-game, then re-run this script.')
    raise SystemExit(1)


def _group_by_namespace(dim_data: dict) -> dict:
    by_namespace = defaultdict(lambda: defaultdict(_empty_sections))
    for dim_id, entries in dim_data.items():
        for entry in entries:
            namespace = entry['biome'].split(':')[0]
            section   = depth_section(entry['parameters']['depth'])
            by_namespace[namespace][dim_id][section].append(entry)
    for namespace_dims in by_namespace.values():
        for sections in namespace_dims.values():
            for section_list in sections.values():
                section_list.sort(key=lambda entry: entry['biome'])
    return by_namespace


def _write_output(by_namespace: dict) -> None:
    OUT_DIR.mkdir(exist_ok=True)
    for namespace, dims in sorted(by_namespace.items()):
        output = {'namespace': namespace, 'dimensions': {}}
        for dim_id, sections in sorted(dims.items()):
            output['dimensions'][dim_id] = sections
            counts = '  '.join(f'{k}: {len(v)}' for k, v in sections.items() if v)
            print(f'  {namespace}  {dim_id}  ({counts})')
        path = OUT_DIR / f'{namespace}.json'
        path.write_text(json.dumps(output, indent=2), encoding='utf-8')
    print(f'\nWritten {len(by_namespace)} file(s) to {OUT_DIR}/')


def main() -> None:
    clean        = _read_clean_log()
    dim_data     = _parse_noise_data(clean)
    by_namespace = _group_by_namespace(dim_data)
    _write_output(by_namespace)


main()
