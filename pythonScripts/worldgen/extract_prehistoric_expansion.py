"""
extract_prehistoric_expansion.py
=================================
Extracts biome data for Shineals Prehistoric Expansion.

This mod registers biomes via vanilla Climate$ParameterPoint directly from Java
code — no TerraBlender region files exist in the jar. Parameters were
reverse-engineered by disassembling the constant pool and bytecode of
ShinealsPrehistoricExpansionModBiomes.class.

Each biome has 2 addParameterPoint calls (positive and negative weirdness),
both with identical climate parameters. Only surface biomes (depth = 0.0).

Confirmed parameters (direct ldc+invokestatic bytecode patterns):
  juniper_forest  — continentalness: [-0.11, 0.55], erosion: [0.45, 0.5]
  ginkgo_forest   — temperature:     [-0.5,  0.5],  humidity: [-0.6, 0.1]

Remaining parameters per biome loaded from pre-stored local variables —
exact values undetermined without full astore/aload chain tracing.
Pool residual floats: {0.5, 0.3, -1.0} and {0.5, 0.55, -1.0} respectively.
Approximate ranges used where confirmed values are unavailable.

Output: worldgen_data/shineals_prehistoric_expansion.json

Usage:
    python pythonScripts/worldgen/extract_prehistoric_expansion.py
    (run from repo root)
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

OUT_DIR  = Path('worldgen_data')
OUT_FILE = OUT_DIR / 'shineals_prehistoric_expansion.json'
NAMESPACE = 'shineals_prehistoric_expansion'


# Confirmed via bytecode disassembly. '~' prefix = approximated from pool residuals.
BIOME_PARAMETERS = {
    'juniper_forest': {
        'temperature':     [-0.5, 0.5],    # ~ residual: pool has 0.3, 0.5
        'humidity':        [0.3,  1.0],    # ~ residual: pool has 0.3, -1.0
        'continentalness': [-0.11, 0.55],  # confirmed
        'erosion':         [0.45, 0.5],    # confirmed
        'depth':           [0.0, 0.0],     # surface biome (point 0)
        'weirdness':       [-1.0, 1.0],    # ~ full range (typical MCreator default)
        'offset':          0.0,
    },
    'ginkgo_forest': {
        'temperature':     [-0.5, 0.5],    # confirmed
        'humidity':        [-0.6, 0.1],    # confirmed
        'continentalness': [0.5,  1.0],    # ~ residual: pool has 0.5, 0.55
        'erosion':         [-1.0, 0.55],   # ~ residual: pool has -1.0, 0.55
        'depth':           [0.0, 0.0],     # surface biome (point 0)
        'weirdness':       [-1.0, 1.0],    # ~ full range
        'offset':          0.0,
    },
}


def _build_entry(biome_name: str, params: dict) -> dict:
    return {
        'biome': f'{NAMESPACE}:{biome_name}',
        'parameters': {
            'temperature':     params['temperature'],
            'humidity':        params['humidity'],
            'continentalness': params['continentalness'],
            'erosion':         params['erosion'],
            'depth':           params['depth'],
            'weirdness':       params['weirdness'],
            'offset':          params['offset'],
        },
    }


def _build_sections() -> dict:
    sections = {'surface': [], 'underground': [], 'caves': [], 'deep': []}
    for biome_name, params in sorted(BIOME_PARAMETERS.items()):
        sections['surface'].append(_build_entry(biome_name, params))
    return sections


def main() -> None:
    sections = _build_sections()
    output = {
        'namespace':  NAMESPACE,
        'note':       'Parameters partially reverse-engineered from bytecode. '
                      'Values marked ~ are approximated from constant pool residuals.',
        'dimensions': {'minecraft:overworld': sections},
    }

    OUT_DIR.mkdir(exist_ok=True)
    OUT_FILE.write_text(json.dumps(output, indent=2), encoding='utf-8')

    total = sum(len(v) for v in sections.values())
    print(f'Written {total} biomes -> {OUT_FILE}')
    for biome_name in BIOME_PARAMETERS:
        print(f'  {NAMESPACE}:{biome_name}')


main()
