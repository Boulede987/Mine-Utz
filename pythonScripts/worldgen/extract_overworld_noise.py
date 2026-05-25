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
from pathlib import Path

LOG   = Path('minecraft/logs/latest.log')
PAXI  = Path('minecraft/config/paxi/datapacks/mineutz/data/mineutz/dimension')
EXCL  = {'minecraft:mushroom_fields', 'minecraft:mushroom_fields_shore'}

DIMENSIONS = {
    'pokemon_dimension': 'mineutz:pokemon_dimension',
    'zombie_city':       'mineutz:zombie_city',
}

# ── Extract JSON from log ─────────────────────────────────────────────────────

log_text = LOG.read_text(encoding='utf-8', errors='replace')
# Strip log prefixes ([timestamp] [thread/LEVEL] [source/]: ) from each line
def strip_prefix(line):
    return re.sub(r'^\[[^\]]+\]\s+\[[^\]]+\]\s+\[[^\]]+\]:\s*', '', line)

lines = [strip_prefix(l) for l in log_text.splitlines()]
clean = '\n'.join(lines)

match = re.search(
    r'===== OVERWORLD NOISE PARAMS START =====\n(.+?)\n===== OVERWORLD NOISE PARAMS END =====',
    clean, re.DOTALL
)
if not match:
    print('ERROR: marker not found in log. Run /dump_overworld_noise first.')
    raise SystemExit(1)

data   = json.loads(match.group(1).strip())
all_biomes = data['biomes']
print(f'Loaded {len(all_biomes)} biome entries from log.')

# ── Filter out mushroom biomes ────────────────────────────────────────────────

filtered = [e for e in all_biomes if e['biome'] not in EXCL]
removed  = [e['biome'] for e in all_biomes if e['biome'] in EXCL]
print(f'Removed {len(removed)} entries: {removed}')
print(f'Remaining: {len(filtered)} entries.')

# ── Build biome_source block ──────────────────────────────────────────────────

def make_param(values):
    if isinstance(values, list):
        return values  # already [min, max]
    return values      # scalar offset

biome_source = {
    'type': 'minecraft:multi_noise',
    'biomes': [
        {
            'biome': e['biome'],
            'parameters': {
                'temperature':     e['parameters']['temperature'],
                'humidity':        e['parameters']['humidity'],
                'continentalness': e['parameters']['continentalness'],
                'erosion':         e['parameters']['erosion'],
                'depth':           e['parameters']['depth'],
                'weirdness':       e['parameters']['weirdness'],
                'offset':          e['parameters']['offset'],
            }
        }
        for e in filtered
    ]
}

# ── Write dimension JSONs ─────────────────────────────────────────────────────

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

print('\nDone. Restart the game to apply.')
