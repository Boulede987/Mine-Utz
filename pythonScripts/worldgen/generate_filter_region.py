"""
generate_filter_region.py
=========================
Reads worldgen_data/merged/*.json (produced by merge_biome_parameters.py) and
prints ready-to-paste Java addBiome() calls for FilterRegion.addBiomes().

Run merge_biome_parameters.py first if worldgen_data/merged/ is missing or stale.

Usage:
    python pythonScripts/worldgen/generate_filter_region.py biome_id [biome_id ...]

Example:
    python pythonScripts/worldgen/generate_filter_region.py \\
        shineals_prehistoric_expansion:ginkgo_forest \\
        biomeswevegone:white_mangrove_marshes
"""

import json
import sys
from pathlib import Path

MERGED_DATA_DIR = Path('worldgen_data') / 'merged'
PARAMETER_AXES  = ['temperature', 'humidity', 'continentalness', 'erosion', 'depth', 'weirdness']


def load_merged_surface_entries() -> dict[str, dict]:
    """Return {biome_id: parameter_dict} for all surface entries in worldgen_data/merged/."""
    if not MERGED_DATA_DIR.exists():
        print('ERROR: worldgen_data/merged/ not found. Run merge_biome_parameters.py first.')
        raise SystemExit(1)

    entries_by_biome: dict[str, dict] = {}
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        for dimension_sections in data.get('dimensions', {}).values():
            for entry in dimension_sections.get('surface', []):
                entries_by_biome[entry['biome']] = entry['parameters']
    return entries_by_biome


def format_parameter(axis: str, values: list[float]) -> str:
    parameter_min, parameter_max = values[0], values[1]
    if parameter_min == parameter_max:
        return f'Climate.Parameter.point({parameter_min}f)'
    return f'Climate.Parameter.span({parameter_min}f, {parameter_max}f)'


def format_add_biome_call(biome_id: str, parameters: dict) -> str:
    namespace, path = biome_id.split(':', 1)
    parameter_lines = [f'            {format_parameter(axis, parameters[axis])}' for axis in PARAMETER_AXES]
    parameters_block = ',\n'.join(parameter_lines)
    return (
        f'        addBiome(mapper,\n'
        f'            Climate.parameters(\n'
        f'{parameters_block},\n'
        f'            {parameters["offset"]}f\n'
        f'            ),\n'
        f'            ResourceKey.create(Registries.BIOME,\n'
        f'                ResourceLocation.fromNamespaceAndPath("{namespace}", "{path}"))\n'
        f'        );'
    )


def main() -> None:
    requested_biomes = sys.argv[1:]
    if not requested_biomes:
        print('Usage: python pythonScripts/worldgen/generate_filter_region.py biome_id [biome_id ...]')
        raise SystemExit(1)

    all_entries   = load_merged_surface_entries()
    output_blocks = []

    for biome_id in requested_biomes:
        if biome_id not in all_entries:
            print(f'WARNING: {biome_id} not found in merged data — skipping', file=sys.stderr)
            continue
        output_blocks.append(format_add_biome_call(biome_id, all_entries[biome_id]))

    print('\n\n'.join(output_blocks))


main()
