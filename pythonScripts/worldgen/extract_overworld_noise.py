"""
extract_overworld_noise.py
==========================
Reads the overworld multi_noise biome parameter list dumped by /dump_overworld_noise
from logs/latest.log, then writes two dimension JSON files to the Paxi datapack
with mushroom_fields entries removed.

Usage:
    python pythonScripts/extract_overworld_noise.py
"""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths import LATEST_LOG, ROOT

PAXI            = ROOT / 'minecraft' / 'config' / 'paxi' / 'datapacks' / 'mineutz' / 'data' / 'mineutz' / 'dimension'
EXCLUDED_BIOMES = {'minecraft:mushroom_fields', 'minecraft:mushroom_fields_shore'}

DIMENSIONS = {
    'pokemon_dimension': 'mineutz:pokemon_dimension',
    'zombie_city':       'mineutz:zombie_city',
}


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


def _filter_biomes(all_biomes: list) -> list:
    filtered = [entry for entry in all_biomes if entry['biome'] not in EXCLUDED_BIOMES]
    removed  = [entry['biome'] for entry in all_biomes if entry['biome'] in EXCLUDED_BIOMES]
    print(f'Removed {len(removed)} entries: {removed}')
    print(f'Remaining: {len(filtered)} entries.')
    return filtered


def _build_biome_source(filtered: list) -> dict:
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
            for entry in filtered
        ]
    }


def _write_dimension_files(biome_source: dict) -> None:
    for dim_name, type_id in DIMENSIONS.items():
        dim_json = {
            'type': type_id,
            'generator': {
                'type': 'minecraft:noise',
                'biome_source': biome_source,
                'settings': 'minecraft:overworld',
            }
        }
        out = PAXI / f'{dim_name}.json'
        out.write_text(json.dumps(dim_json, indent=2), encoding='utf-8')
        print(f'Written: {out}')


def main() -> None:
    all_biomes   = _load_overworld_biomes()
    filtered     = _filter_biomes(all_biomes)
    biome_source = _build_biome_source(filtered)
    _write_dimension_files(biome_source)
    print('\nDone. Restart the game to apply.')


main()
