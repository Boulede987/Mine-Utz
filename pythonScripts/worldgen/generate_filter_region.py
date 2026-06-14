"""
generate_filter_region.py
=========================
Reads dimension_biome_planner.xlsx ✓ columns and generates TerraBlender
region Java files + FilterMod.java into the companion mod.

Overworld → FilterRegion.java        (RegionType.OVERWORLD, always)
Nether    → FilterNetherRegion.java  (RegionType.NETHER, only if ✓ biomes exist)
End       → FilterEndBiomes.java     (EndBiomeRegistry calls, only if ✓ biomes exist)
FilterMod.java is regenerated to register whichever regions were written.

Run after steps 21 (merge) and 22 (complete planner).

Usage:
    python pythonScripts/worldgen/generate_filter_region.py
"""

import json
import sys
from pathlib import Path

import openpyxl

sys.stdout.reconfigure(encoding='utf-8')

PLANNER_FILE     = Path('dimension_biome_planner.xlsx')
SHEET_NAME       = 'Biome Planner'
OVERWORLD_HEADER = 'Overworld'
NETHER_HEADER    = 'Nether'
END_HEADER       = 'End'
CHECK_MARK       = '✓'
MERGED_DATA_DIR  = Path('worldgen_data') / 'merged'
END_SECTIONS     = ['highlands', 'midlands', 'edge', 'islands']

MOD_SRC = Path(
    'companion-mods/terrablender-custom-region'
    '/src/main/java/com/mineutze/filtermod'
)
FILTER_REGION        = MOD_SRC / 'FilterRegion.java'
FILTER_NETHER_REGION = MOD_SRC / 'FilterNetherRegion.java'
FILTER_END_BIOMES    = MOD_SRC / 'FilterEndBiomes.java'
FILTER_MOD           = MOD_SRC / 'FilterMod.java'

PARAMETER_AXES = ['temperature', 'humidity', 'continentalness', 'erosion', 'depth', 'weirdness']

_REGION_TEMPLATE = """\
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

public class {class_name} extends Region {{

    public {class_name}(ResourceLocation name, int weight) {{
        super(name, RegionType.{region_type}, weight);
    }}

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {{
{body}
    }}
}}
"""

_END_BIOMES_TEMPLATE = """\
package com.mineutze.filtermod;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import terrablender.api.EndBiomeRegistry;

public class FilterEndBiomes {{

    public static void setup() {{
{calls}
    }}

    private static ResourceKey<Biome> key(String ns, String path) {{
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(ns, path));
    }}
}}
"""

_MOD_TEMPLATE = """\
package com.mineutze.filtermod;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import terrablender.api.Regions;

@Mod("mineutze_filter")
public class FilterMod {{

    public FilterMod(IEventBus modEventBus) {{
        modEventBus.addListener(this::onCommonSetup);
    }}

    private void onCommonSetup(FMLCommonSetupEvent event) {{
        event.enqueueWork(() -> {{
{registrations}
        }});
    }}
}}
"""

_REGISTRATION = (
    '            Regions.register(new {class_name}(\n'
    '                ResourceLocation.fromNamespaceAndPath("mineutze_filter", "{loc}"),\n'
    '                100_000_000\n'
    '            ));'
)
_END_SETUP_CALL = '            FilterEndBiomes.setup();'


# ---------------------------------------------------------------------------
# Loaders
# ---------------------------------------------------------------------------

def load_column_biomes(ws, headers: list, column_name: str) -> list[str]:
    """Return biome IDs marked ✓ in the named column. Returns [] if column absent."""
    try:
        col_idx = headers.index(column_name)
    except ValueError:
        return []
    return [
        str(row[0]) for row in ws.iter_rows(min_row=2, values_only=True)
        if row[0] and ':' in str(row[0]) and row[col_idx] == CHECK_MARK
    ]


def load_merged_params() -> dict:
    """
    {biome_id: parameters} across all merged namespace files.
    Section priority: surface > underground > caves > deep.
    Surface entry wins so biomes that mods also register at depth=1.0 aren't
    pushed underground.
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
                    if entry['biome'] not in lookup:
                        lookup[entry['biome']] = entry['parameters']
    return lookup


def load_merged_end_data() -> dict:
    """{biome_id: {placement_type, weight}} from minecraft:the_end across all merged files."""
    if not MERGED_DATA_DIR.exists():
        return {}

    lookup: dict = {}
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        end_sections = data.get('dimensions', {}).get('minecraft:the_end', {})
        for placement_type in END_SECTIONS:
            for entry in end_sections.get(placement_type, []):
                biome = entry['biome']
                if biome not in lookup:
                    lookup[biome] = {'placement_type': placement_type, 'weight': entry['weight']}
    return lookup


# ---------------------------------------------------------------------------
# Formatters
# ---------------------------------------------------------------------------

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


def build_region(biome_ids: list[str], all_params: dict,
                 class_name: str, region_type: str, dest: Path) -> int:
    """Write a region Java file. Returns number of biomes written."""
    blocks  = []
    missing = []
    for biome_id in biome_ids:
        if biome_id not in all_params:
            missing.append(biome_id)
            continue
        blocks.append(fmt_add_biome(biome_id, all_params[biome_id]))

    if missing:
        print(f'  WARNING: {len(missing)} biome(s) not in merged data — skipped:')
        for m in missing:
            print(f'    - {m}')

    body = '\n\n'.join(blocks)
    java = _REGION_TEMPLATE.format(class_name=class_name, region_type=region_type, body=body)
    dest.write_text(java, encoding='utf-8')
    print(f'  Wrote {len(blocks)} biome(s) → {dest.name}')
    return len(blocks)


def build_end_biomes(biome_ids: list[str], end_data: dict, dest: Path) -> int:
    """Write FilterEndBiomes.java. Returns number of biomes written."""
    calls   = []
    missing = []
    for biome_id in biome_ids:
        if biome_id not in end_data:
            missing.append(biome_id)
            continue
        info     = end_data[biome_id]
        ptype    = info['placement_type']
        weight   = info['weight']
        ns, path = biome_id.split(':', 1)
        method   = f'register{ptype.capitalize()}Biome'
        calls.append(f'        EndBiomeRegistry.{method}(key("{ns}", "{path}"), {weight});')

    if missing:
        print(f'  WARNING: {len(missing)} End biome(s) not in merged data — skipped:')
        for m in missing:
            print(f'    - {m}')

    java = _END_BIOMES_TEMPLATE.format(calls='\n'.join(calls))
    dest.write_text(java, encoding='utf-8')
    print(f'  Wrote {len(calls)} biome(s) → {dest.name}')
    return len(calls)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    if not PLANNER_FILE.exists():
        print(f'ERROR: {PLANNER_FILE} not found.')
        raise SystemExit(1)

    ws      = openpyxl.load_workbook(PLANNER_FILE)[SHEET_NAME]
    headers = [cell.value for cell in ws[1]]

    overworld_biomes = load_column_biomes(ws, headers, OVERWORLD_HEADER)
    nether_biomes    = load_column_biomes(ws, headers, NETHER_HEADER)
    end_biomes       = load_column_biomes(ws, headers, END_HEADER)

    if not overworld_biomes:
        print('No biomes marked ✓ in Overworld column — nothing to write.')
        raise SystemExit(0)

    all_params = load_merged_params()
    end_data   = load_merged_end_data()

    # Overworld region (always)
    build_region(overworld_biomes, all_params, 'FilterRegion', 'OVERWORLD', FILTER_REGION)

    # Nether region (only if biomes exist)
    has_nether = bool(nether_biomes)
    if has_nether:
        build_region(nether_biomes, all_params, 'FilterNetherRegion', 'NETHER', FILTER_NETHER_REGION)
    elif FILTER_NETHER_REGION.exists():
        FILTER_NETHER_REGION.unlink()
        print(f'  Removed {FILTER_NETHER_REGION.name} (no Nether biomes selected).')

    # End biomes (only if biomes exist)
    has_end = bool(end_biomes)
    if has_end:
        build_end_biomes(end_biomes, end_data, FILTER_END_BIOMES)
    elif FILTER_END_BIOMES.exists():
        FILTER_END_BIOMES.unlink()
        print(f'  Removed {FILTER_END_BIOMES.name} (no End biomes selected).')

    # FilterMod.java — register whichever regions exist
    registrations_list = [
        _REGISTRATION.format(class_name='FilterRegion', loc='overworld_filter'),
    ]
    if has_nether:
        registrations_list.append(
            _REGISTRATION.format(class_name='FilterNetherRegion', loc='nether_filter'),
        )
    if has_end:
        registrations_list.append(_END_SETUP_CALL)

    dims_label = ' + '.join(filter(None, [
        'overworld',
        'nether' if has_nether else '',
        'end'    if has_end    else '',
    ]))
    registrations = '\n'.join(registrations_list)
    FILTER_MOD.write_text(_MOD_TEMPLATE.format(registrations=registrations), encoding='utf-8')
    print(f'  Wrote FilterMod.java ({dims_label})')

    print('\nRebuild the companion mod (deployMod in IntelliJ) to apply.')


main()
