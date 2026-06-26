"""
worldgen/terrablender_codegen.py
================================
Java source generation for TerraBlender companion mod.
Templates, formatters, and builders — no file I/O except writing the final .java files.
"""

from pathlib import Path

TERRABLENDER_REGION_WEIGHT = 100_000_000
PARAMETER_AXES             = ['temperature', 'humidity', 'continentalness', 'erosion', 'depth', 'weirdness']
_JAVA_PARAM_INDENT         = '            '

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

    @FunctionalInterface
    private interface BiomeMapper extends Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> {{}}

    public {class_name}(ResourceLocation name, int weight) {{
        super(name, RegionType.{region_type}, weight);
    }}

    @Override
    public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {{
        BiomeMapper m = mapper::accept;
{dispatcher}
    }}

{namespace_methods}
    private static ResourceKey<Biome> biomeKey(String namespace, String path) {{
        return ResourceKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(namespace, path));
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
    '                {weight}\n'
    '            ));'
)
_END_SETUP_CALL = '            FilterEndBiomes.setup();'


# ---------------------------------------------------------------------------
# Formatters
# ---------------------------------------------------------------------------

def _fmt_param(values: list) -> str:
    lo, hi = values[0], values[1]
    if lo == hi:
        return f'Climate.Parameter.point({lo}f)'
    return f'Climate.Parameter.span({lo}f, {hi}f)'


def _fmt_add_biome_call(param: dict) -> str:
    params = ',\n'.join(f'{_JAVA_PARAM_INDENT}{_fmt_param(param[axis])}' for axis in PARAMETER_AXES)
    return (
        f'        addBiome(mapper, Climate.parameters(\n'
        f'{params},\n'
        f'{_JAVA_PARAM_INDENT}{param["offset"]}f\n'
        f'        ), key);'
    )


def _fmt_per_biome_method(biome_id: str, params_list: list[dict]) -> str:
    namespace, path = biome_id.split(':', 1)
    method = f'{namespace}_{path}'
    calls  = '\n'.join(_fmt_add_biome_call(param) for param in params_list)
    return (
        f'    private void {method}(BiomeMapper mapper) {{\n'
        f'        var key = biomeKey("{namespace}", "{path}");\n'
        f'{calls}\n'
        f'    }}'
    )


def _fmt_namespace_method(namespace: str, paths: list[str]) -> str:
    calls = '\n'.join(f'        {namespace}_{path}(mapper);' for path in paths)
    return (
        f'    private void add_{namespace}(BiomeMapper mapper) {{\n'
        f'{calls}\n'
        f'    }}'
    )


def _fmt_section_divider(namespace: str) -> str:
    prefix = f'    // ── {namespace} '
    return prefix + '─' * max(4, 80 - len(prefix))


# ---------------------------------------------------------------------------
# Builders
# ---------------------------------------------------------------------------

def build_region(
    biome_ids: list[str],
    all_params: dict[str, list[dict]],
    class_name: str,
    region_type: str,
    dest: Path,
) -> int:
    by_namespace: dict[str, dict[str, list[dict]]] = {}
    missing: list[str] = []

    for biome_id in biome_ids:
        if biome_id not in all_params:
            missing.append(biome_id)
            continue
        namespace, path = biome_id.split(':', 1)
        by_namespace.setdefault(namespace, {})[path] = all_params[biome_id]

    if missing:
        print(f'  WARNING: {len(missing)} biome(s) not in merged data — skipped:')
        for missing_id in missing:
            print(f'    - {missing_id}')

    namespaces    = sorted(by_namespace.keys())
    dispatcher    = '\n'.join(f'        add_{ns}(m);' for ns in namespaces)
    method_blocks: list[str] = []
    for ns in namespaces:
        paths = sorted(by_namespace[ns].keys())
        method_blocks.append(_fmt_section_divider(ns))
        method_blocks.append(_fmt_namespace_method(ns, paths))
        for path in paths:
            method_blocks.append(_fmt_per_biome_method(f'{ns}:{path}', by_namespace[ns][path]))

    namespace_methods = '\n\n'.join(method_blocks) + '\n\n'
    total_biomes      = sum(len(paths) for paths in by_namespace.values())
    total_entries     = sum(
        len(param_list) for paths in by_namespace.values() for param_list in paths.values()
    )

    dest.write_text(
        _REGION_TEMPLATE.format(
            class_name=class_name,
            region_type=region_type,
            dispatcher=dispatcher,
            namespace_methods=namespace_methods,
        ),
        encoding='utf-8',
    )
    print(f'  Wrote {total_biomes} biomes ({total_entries} entries) → {dest.name}')
    return total_biomes


def build_end_biomes(biome_ids: list[str], end_data: dict, dest: Path) -> int:
    calls:   list[str] = []
    missing: list[str] = []

    for biome_id in biome_ids:
        if biome_id not in end_data:
            missing.append(biome_id)
            continue
        info   = end_data[biome_id]
        ptype  = info['placement_type']
        weight = info['weight']
        ns, path = biome_id.split(':', 1)
        method = f'register{ptype.capitalize()}Biome'
        calls.append(f'        EndBiomeRegistry.{method}(key("{ns}", "{path}"), {weight});')

    if missing:
        print(f'  WARNING: {len(missing)} End biome(s) not in merged data — skipped:')
        for missing_id in missing:
            print(f'    - {missing_id}')

    dest.write_text(_END_BIOMES_TEMPLATE.format(calls='\n'.join(calls)), encoding='utf-8')
    print(f'  Wrote {len(calls)} biome(s) → {dest.name}')
    return len(calls)


def build_filter_mod(dest: Path, has_nether: bool, has_end: bool) -> None:
    registrations_list = [
        _REGISTRATION.format(class_name='FilterRegion', loc='overworld_filter', weight=TERRABLENDER_REGION_WEIGHT),
    ]
    if has_nether:
        registrations_list.append(
            _REGISTRATION.format(class_name='FilterNetherRegion', loc='nether_filter', weight=TERRABLENDER_REGION_WEIGHT),
        )
    if has_end:
        registrations_list.append(_END_SETUP_CALL)

    dest.write_text(_MOD_TEMPLATE.format(registrations='\n'.join(registrations_list)), encoding='utf-8')
    dims_label = ' + '.join(filter(None, [
        'overworld',
        'nether' if has_nether else '',
        'end'    if has_end    else '',
    ]))
    print(f'  Wrote FilterMod.java ({dims_label})')
