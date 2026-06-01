"""
extract_overworld_noise.py
==========================
Reads the overworld multi_noise biome parameter list dumped by /dump_overworld_noise
from logs/latest.log and writes a reference JSON to worldgen_data/.

Output: worldgen_data/overworld_noise_reference.json
  Contains the complete biome_source block for all overworld biomes.
  Use this as a copy-paste reference when manually authoring custom dimension JSONs.

  Do NOT run this to populate dimension files — biome lists for custom dimensions
  are managed manually via dimension_biome_planner.xlsx.

Usage:
    python pythonScripts/worldgen/extract_overworld_noise.py
"""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths import LATEST_LOG

OUT_DIR  = Path('worldgen_data')
OUT_FILE = OUT_DIR / 'overworld_noise_reference.json'


def _strip_log_prefix(line: str) -> str:
    return re.sub(r'^\[[^\]]+\]\s+\[[^\]]+\]\s+\[[^\]]+\]:\s*', '', line)


def _load_overworld_biomes() -> list:
    log_text = LATEST_LOG.read_text(encoding='utf-8', errors='replace')
    lines    = [_strip_log_prefix(line) for line in log_text.splitlines()]
    clean    = '\n'.join(lines)

    match = re.search(
        r'===== OVERWORLD NOISE PARAMS START =====\n(.+?)\n===== OVERWORLD NOISE PARAMS END =====',
        clean, re.DOTALL
    )
    if not match:
        print('ERROR: marker not found in log. Run /dump_overworld_noise first.')
        raise SystemExit(1)

    data = json.loads(match.group(1).strip())
    all_biomes = data['biomes']
    print(f'Loaded {len(all_biomes)} biome entries from log.')
    return all_biomes


def _build_biome_source(all_biomes: list) -> dict:
    return {
        'type': 'minecraft:multi_noise',
        'biomes': [
            {
                'biome': entry['biome'],
                'parameters': {
                    'temperature':     entry['parameters']['temperature'],
                    'humidity':        entry['parameters']['humidity'],
                    'continentalness': entry['parameters']['continentalness'],
                    'erosion':         entry['parameters']['erosion'],
                    'depth':           entry['parameters']['depth'],
                    'weirdness':       entry['parameters']['weirdness'],
                    'offset':          entry['parameters']['offset'],
                }
            }
            for entry in all_biomes
        ]
    }


def _write_reference(biome_source: dict) -> None:
    OUT_DIR.mkdir(exist_ok=True)
    OUT_FILE.write_text(json.dumps(biome_source, indent=2), encoding='utf-8')
    print(f'Written reference -> {OUT_FILE}')
    print('Copy individual biome entries from this file when editing dimension JSONs manually.')


def main() -> None:
    all_biomes   = _load_overworld_biomes()
    biome_source = _build_biome_source(all_biomes)
    _write_reference(biome_source)


main()
