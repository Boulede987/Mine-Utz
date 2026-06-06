"""
generate_filter_region.py
=========================
Reads the Overworld column of dimension_biome_planner.xlsx (✓ rows only),
looks up merged climate parameters, and writes the addBiomes() method body
directly into FilterRegion.java.

Run after steps 21 (merge) and 22 (complete planner).

Usage:
    python pythonScripts/worldgen/generate_filter_region.py
"""

import json
import sys
from pathlib import Path

import openpyxl

sys.stdout.reconfigure(encoding='utf-8')

PLANNER_FILE    = Path('dimension_biome_planner.xlsx')
SHEET_NAME      = 'Biome Planner'
OVERWORLD_HEADER = 'Overworld'
CHECK_MARK      = '✓'
MERGED_DATA_DIR = Path('worldgen_data') / 'merged'
FILTER_REGION   = Path(
    'companion-mods/terrablender-custom-region'
    '/src/main/java/com/mineutze/filtermod/FilterRegion.java'
)
PARAMETER_AXES  = ['temperature', 'humidity', 'continentalness', 'erosion', 'depth', 'weirdness']

JAVA_TEMPLATE = """\
package com.mineutze.filtermod;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.api.RegionType;

import java.util.function.Consumer;

public class FilterRegion extends Region {{

    public FilterRegion(ResourceLocation name, int weight) {{
        super(name, RegionType.OVERWORLD, weight);
    }}

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {{
{body}
    }}
}}
"""


def load_overworld_biomes() -> list[str]:
    """Return biome IDs marked ✓ in the Overworld column of the planner."""
    if not PLANNER_FILE.exists():
        print(f'ERROR: {PLANNER_FILE} not found.')
        raise SystemExit(1)

    ws = openpyxl.load_workbook(PLANNER_FILE)[SHEET_NAME]
    headers = [cell.value for cell in ws[1]]

    try:
        overworld_col = headers.index(OVERWORLD_HEADER)
    except ValueError:
        print(f'ERROR: "{OVERWORLD_HEADER}" column not found in planner.')
        raise SystemExit(1)

    biomes = []
    for row in ws.iter_rows(min_row=2, values_only=True):
        biome_id = row[0]
        if biome_id and ':' in str(biome_id) and row[overworld_col] == CHECK_MARK:
            biomes.append(biome_id)
    return biomes


def load_merged_params() -> dict:
    """
    Return {biome_id: parameters} across all merged namespace files.

    Section priority: surface > underground > caves > deep.
    A biome's surface entry wins over its cave/underground entry so that
    surface biomes (which mods also register at depth=1.0 for underground
    binding) are not overwritten with depth=1.0 and pushed underground.
    """
    if not MERGED_DATA_DIR.exists():
        print('ERROR: worldgen_data/merged/ not found. Run step 21 first.')
        raise SystemExit(1)

    lookup: dict = {}
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        for dim_sections in data.get('dimensions', {}).values():
            for section in ['surface', 'underground', 'caves', 'deep']:
                for entry in dim_sections.get(section, []):
                    if entry['biome'] not in lookup:   # first section found wins
                        lookup[entry['biome']] = entry['parameters']
    return lookup


def fmt_param(values: list) -> str:
    lo, hi = values[0], values[1]
    if lo == hi:
        return f'Climate.Parameter.point({lo}f)'
    return f'Climate.Parameter.span({lo}f, {hi}f)'


def fmt_add_biome(biome_id: str, p: dict) -> str:
    namespace, path = biome_id.split(':', 1)
    axes = ',\n'.join(f'                {fmt_param(p[ax])}' for ax in PARAMETER_AXES)
    return (
        f'        addBiome(mapper,\n'
        f'            Climate.parameters(\n'
        f'{axes},\n'
        f'                {p["offset"]}f\n'
        f'            ),\n'
        f'            ResourceKey.create(Registries.BIOME,\n'
        f'                ResourceLocation.fromNamespaceAndPath("{namespace}", "{path}"))\n'
        f'        );'
    )


def main() -> None:
    biome_ids = load_overworld_biomes()
    if not biome_ids:
        print('No biomes marked ✓ in the Overworld column — nothing to write.')
        raise SystemExit(0)

    all_params = load_merged_params()

    blocks = []
    missing = []
    for biome_id in biome_ids:
        if biome_id not in all_params:
            missing.append(biome_id)
            continue
        blocks.append(fmt_add_biome(biome_id, all_params[biome_id]))

    if missing:
        print(f'WARNING: {len(missing)} biome(s) not in merged data — skipped:')
        for m in missing:
            print(f'  - {m}')

    body = '\n\n'.join(blocks)
    java = JAVA_TEMPLATE.format(body=body)

    FILTER_REGION.write_text(java, encoding='utf-8')
    print(f'Wrote {len(blocks)} biome(s) → {FILTER_REGION}')
    print('Rebuild the companion mod (deployMod in IntelliJ) to apply.')


main()
