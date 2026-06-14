"""
scan_terrablender.py
====================
Auto-scans mod jars for TerraBlender nether/end biome registrations
and writes extracted data to worldgen_data/<namespace>.json.

Detects:
  1. Classes extending terrablender.api.Region with RegionType.NETHER
     - Parses direct addBiome() calls in addBiomes() via bytecode
     - Outputs {biome, parameters} entries under minecraft:nether
  2. EndBiomeRegistry.register*Biome() calls in any class
     - Outputs {biome, weight} entries under minecraft:the_end

After running: python pythonScripts/worldgen/merge_biome_parameters.py

Limitations:
  - Direct addBiome() calls only — mods that delegate to helper methods
    are flagged with a warning; add a dedicated parse_<mod>.py for those.
  - Biome IDs resolved from getstatic field names (FIELD_NAME → ns:field_name).
    Mods that build ResourceKeys from string literals at runtime won't be detected.
  - Config-gated biome additions are extracted unconditionally.

Usage:
    python pythonScripts/worldgen/scan_terrablender.py
"""

import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')

MODS_DIR   = Path('minecraft/mods')
OUTPUT_DIR = Path('worldgen_data')

# These namespaces have dedicated parse scripts — scanner skips them.
# Remove an entry here to force re-scan (e.g., to verify parse script accuracy).
SKIP_NAMESPACES = frozenset({'biomesoplenty', 'biomeswevegone', 'terrablender'})

_END_PLACEMENT = {
    'registerHighlandsBiome': 'highlands',
    'registerMidlandsBiome':  'midlands',
    'registerEdgeBiome':      'edge',
    'registerIslandBiome':    'islands',
}

# Binary strings used as quick pre-filters before running javap
_B_REGION  = b'terrablender/api/Region'
_B_END_REG = b'EndBiomeRegistry'

# Bytecode line patterns
_RX_LDC_FLOAT = re.compile(r'\bldc\S*\s+#\d+\s+//\s+float\s+(-?\d[\d.]*(?:[Ee][+-]?\d+)?)')
_RX_BIPUSH    = re.compile(r'\bbipush\s+(-?\d+)')
_RX_SIPUSH    = re.compile(r'\bsipush\s+(-?\d+)')
_RX_ICONST    = re.compile(r'\biconst_(-?\d+|m1)')
_RX_GETSTATIC = re.compile(r'\bgetstatic\s+#\d+\s+//\s+Field\s+([^:]+):')
_RX_INVOKE    = re.compile(
    r'\b(?:invokestatic|invokevirtual|invokeinterface|invokespecial)\s+#\d+[^/]*//\s+'
    r'(?:Method|InterfaceMethod)\s+(\S+)'
)


# ---------------------------------------------------------------------------
# Jar / class utilities
# ---------------------------------------------------------------------------

def _jar_namespace(jar: zipfile.ZipFile) -> str | None:
    """Primary mod namespace — unique data/ namespace, or class-count tiebreak."""
    data_skip = {'minecraft', 'neoforge', 'c', 'forge', 'terrablender'}
    data_ns: set[str] = set()
    for n in jar.namelist():
        if n.startswith('data/') and n.count('/') >= 2:
            ns = n.split('/')[1]
            if ns not in data_skip:
                data_ns.add(ns)
    if len(data_ns) == 1:
        return next(iter(data_ns))
    if not data_ns:
        return None
    # Ambiguous (mod bundles data for other mods): pick the namespace whose
    # Java packages contain the most class files.
    counts: dict[str, int] = {}
    for n in jar.namelist():
        if n.endswith('.class') and '/' in n:
            pkg = n.split('/')[0]
            if pkg in data_ns:
                counts[pkg] = counts.get(pkg, 0) + 1
    return max(counts, key=counts.__getitem__) if counts else None


def _javap(class_data: bytes) -> str:
    with tempfile.NamedTemporaryFile(suffix='.class', delete=False) as f:
        f.write(class_data)
        tmp = Path(f.name)
    try:
        r = subprocess.run(
            ['javap', '-p', '-c', str(tmp)],
            capture_output=True, text=True, timeout=15,
        )
        return r.stdout
    finally:
        tmp.unlink(missing_ok=True)


def _ns_from_owner(owner: str, jar_ns: str) -> str:
    """
    Infer mod namespace from a Java class path.
      'biomesoplenty/api/biome/BOPBiomes'             → 'biomesoplenty'
      'net/minecraft/world/level/biome/Biomes'        → 'minecraft'
      'net/potionstudios/biomeswevegone/worldgen/...' → 'biomeswevegone'
    Falls back to jar_ns if nothing better found.
    """
    parts = owner.split('/')
    first = parts[0] if parts else ''
    if first in ('net', 'com', 'org', 'io', 'dev', 'java', 'javax'):
        company_set = {'minecraft', 'potionstudios', 'teamabnormals', 'fabricmc',
                       'neoforged', 'minecraftforge', 'quiltmc', 'mojang'}
        for part in parts[1:]:
            if part == 'minecraft':
                return 'minecraft'
            if part not in company_set:
                return part
        return jar_ns
    return first or jar_ns


def _resolve_biome(field_ref: str, jar_ns: str) -> str | None:
    """
    'net/minecraft/.../Biomes.NETHER_WASTES'       → 'minecraft:nether_wastes'
    'biomesoplenty/.../BOPBiomes.CRYSTALLINE_CHASM' → 'biomesoplenty:crystalline_chasm'
    """
    if '.' not in field_ref:
        return None
    owner, field = field_ref.rsplit('.', 1)
    ns = _ns_from_owner(owner, jar_ns)
    return f'{ns}:{field.lower()}'


def _iconst_val(m: re.Match) -> int:
    v = m.group(1)
    return -1 if v == 'm1' else int(v)


# ---------------------------------------------------------------------------
# Nether region parser
# ---------------------------------------------------------------------------

def _extract_addbiomes_body(javap_text: str) -> list[str]:
    """Extract lines from the addBiomes() method body."""
    lines = javap_text.splitlines()
    in_method = False
    body: list[str] = []
    for line in lines:
        if not in_method:
            if 'addBiomes(' in line:
                in_method = True
        else:
            # Stop at next method declaration (has access modifier + return type)
            if re.match(r'^  (?:public|private|protected|static|\w+ \w+\()', line):
                break
        if in_method:
            body.append(line)
    return body


def _parse_nether_region(javap_text: str, jar_ns: str) -> tuple[list[dict], bool]:
    """
    Parse direct addBiome() calls from a NETHER Region subclass.
    Returns (entries, has_indirect) — has_indirect=True means unresolved helper calls remain.

    Handles two addBiome signatures used by TerraBlender mods:
      Pattern A: addBiome(Consumer, Climate.ParameterPoint, ResourceKey)
                 — uses Climate.parameters() to build the point first
      Pattern B: addBiome(Consumer, P, P, P, P, P, P, F, ResourceKey)
                 — passes 6 Climate.Parameter values + offset float individually
                 — BoP/common TerraBlender pattern

    Also handles biomeOrFallback(registry, ResourceKey[]) helper: picks index-0 key
    (the mod biome; index-1 is vanilla fallback).
    """
    body = _extract_addbiomes_body(javap_text)
    if not body:
        return [], False

    entries: list[dict] = []
    stack: list[tuple]  = []
    has_indirect        = False

    # Array-construction state for biomeOrFallback pattern
    in_array       = False
    array_last_idx: int | None = None
    array_biome_0: str | None  = None   # preferred (mod) biome at index 0

    for line in body:
        s = line.strip()

        # ── Float pushes ──────────────────────────────────────────────────────
        if m := _RX_LDC_FLOAT.search(s):
            stack.append(('f', float(m.group(1)))); continue
        if 'fconst_0' in s: stack.append(('f', 0.0)); continue
        if 'fconst_1' in s: stack.append(('f', 1.0)); continue
        if 'fconst_2' in s: stack.append(('f', 2.0)); continue
        if 'fneg' in s and stack and stack[-1][0] == 'f':
            v = stack.pop()[1]; stack.append(('f', -v)); continue
        if 'i2f' in s and stack and stack[-1][0] in ('i', 'f'):
            v = stack.pop()[1]; stack.append(('f', float(v))); continue

        # ── Int pushes ────────────────────────────────────────────────────────
        if m := _RX_ICONST.search(s):
            val = _iconst_val(m)
            if in_array:
                array_last_idx = val
            else:
                stack.append(('i', val))
            continue
        if m := _RX_BIPUSH.search(s): stack.append(('i', int(m.group(1)))); continue
        if m := _RX_SIPUSH.search(s): stack.append(('i', int(m.group(1)))); continue

        # ── Array construction (biomeOrFallback pattern) ──────────────────────
        if 'anewarray' in s and 'ResourceKey' in s:
            if stack and stack[-1][0] == 'i':
                stack.pop()  # pop array-size iconst that preceded anewarray
            in_array = True
            array_last_idx = None
            array_biome_0  = None
            continue
        if in_array and ('aastore' in s or 'dup' in s or 'aload' in s):
            continue

        # ── getstatic: biome ResourceKey ─────────────────────────────────────
        if m := _RX_GETSTATIC.search(s):
            if 'ResourceKey' in s:
                bid = _resolve_biome(m.group(1).strip(), jar_ns)
                if bid:
                    if in_array:
                        if array_last_idx == 0:
                            array_biome_0 = bid  # preferred (mod) biome
                    else:
                        stack.append(('biome', bid))
            continue

        # ── Method invocations ────────────────────────────────────────────────
        if m := _RX_INVOKE.search(s):
            sig = m.group(1)

            # biomeOrFallback helper — resolve to index-0 biome
            if in_array and ('Fallback' in sig or 'fallback' in sig.lower() or 'biomeOr' in sig):
                in_array = False
                if array_biome_0:
                    stack.append(('biome', array_biome_0))
                array_biome_0 = None
                continue

            # Any other invoke inside array construction — give up, push what we have
            if in_array:
                in_array = False
                if array_biome_0:
                    stack.append(('biome', array_biome_0))
                array_biome_0 = None

            # Climate.Parameter.point(F) → [f, f]
            if 'Climate' in sig and 'point:(F)' in sig:
                if stack and stack[-1][0] == 'f':
                    v = round(stack.pop()[1], 6)
                    stack.append(('param', [v, v]))
                continue

            # Climate.Parameter.span(FF) → [lo, hi]
            if 'Climate' in sig and 'span:(FF)' in sig:
                if len(stack) >= 2 and stack[-2][0] == 'f' and stack[-1][0] == 'f':
                    hi = stack.pop()[1]
                    lo = stack.pop()[1]
                    stack.append(('param', [round(lo, 6), round(hi, 6)]))
                continue

            # Pattern A: Climate.parameters(P×6, F) → ParameterPoint
            if 'Climate' in sig and 'parameters:' in sig:
                offset = 0.0
                if stack and stack[-1][0] == 'f':
                    offset = round(stack.pop()[1], 6)
                axes: list | None = []
                for _ in range(6):
                    if stack and stack[-1][0] == 'param':
                        axes.append(stack.pop()[1])
                    else:
                        axes = None; break
                if axes is not None:
                    weird, depth, erosion, cont, hum, temp = axes
                    stack.append(('pp', {
                        'temperature': temp, 'humidity': hum,
                        'continentalness': cont, 'erosion': erosion,
                        'depth': depth, 'weirdness': weird,
                        'offset': offset,
                    }))
                continue

            if 'addBiome' in sig:
                if 'ParameterPoint' in sig or 'pp' in [i[0] for i in stack]:
                    # Pattern A: addBiome(Consumer, ParameterPoint, ResourceKey)
                    biome = pp = None
                    for item in reversed(stack):
                        if item[0] == 'biome' and not biome:
                            biome = item[1]
                        elif item[0] == 'pp' and not pp:
                            pp = item[1]
                        if biome and pp:
                            break
                    if biome and pp:
                        entries.append({'biome': biome, 'parameters': pp})
                else:
                    # Pattern B: addBiome(Consumer, P×6, F, ResourceKey)
                    biome  = stack.pop()[1] if stack and stack[-1][0] == 'biome' else None
                    offset = round(stack.pop()[1], 6) if stack and stack[-1][0] == 'f' else 0.0
                    axes_b: list | None = []
                    for _ in range(6):
                        if stack and stack[-1][0] == 'param':
                            axes_b.append(stack.pop()[1])
                        else:
                            axes_b = None; break
                    if biome and axes_b is not None and len(axes_b) == 6:
                        weird, depth, erosion, cont, hum, temp = axes_b
                        entries.append({'biome': biome, 'parameters': {
                            'temperature': temp, 'humidity': hum,
                            'continentalness': cont, 'erosion': erosion,
                            'depth': depth, 'weirdness': weird,
                            'offset': offset,
                        }})
                    elif not biome:
                        has_indirect = True  # biome came from unhandled helper
                stack.clear()
                continue

            # Unknown invoke not related to biome construction
            if not any(k in sig for k in (
                'Climate', 'Consumer', '<init>', 'RegionType', 'Pair',
                'ResourceKey', 'Registries', 'ResourceLocation', 'Registry'
            )):
                has_indirect = True

    return entries, has_indirect


# ---------------------------------------------------------------------------
# End registry parser
# ---------------------------------------------------------------------------

def _parse_end_registrations(javap_text: str, jar_ns: str) -> list[dict]:
    """
    Scan for EndBiomeRegistry.register*Biome() calls.
    Uses backward scan from each call site to avoid state pollution from
    unrelated instructions in other methods.
    """
    lines   = javap_text.splitlines()
    entries: list[dict] = []

    for i, line in enumerate(lines):
        s = line.strip()
        m = _RX_INVOKE.search(s)
        if not m:
            continue
        sig = m.group(1)

        # Match both EndBiomeRegistry.registerXxxBiome() direct calls and
        # mod-local wrappers (same method name, no class prefix in javap output).
        placement = None
        for method, p in _END_PLACEMENT.items():
            if method in sig:
                placement = p
                break
        if not placement:
            continue

        # Scan backward up to 15 lines for the weight (int push) and biome (getstatic)
        biome_id = None
        weight   = None
        for j in range(i - 1, max(i - 15, -1), -1):
            prev = lines[j].strip()
            if weight is None:
                if m2 := _RX_BIPUSH.search(prev):   weight = int(m2.group(1))
                elif m2 := _RX_SIPUSH.search(prev): weight = int(m2.group(1))
                elif m2 := _RX_ICONST.search(prev): weight = _iconst_val(m2)
            if biome_id is None and 'ResourceKey' in prev:
                if m2 := _RX_GETSTATIC.search(prev):
                    biome_id = _resolve_biome(m2.group(1).strip(), jar_ns)
            if biome_id and weight is not None:
                break

        if biome_id and weight is not None and weight > 0:
            entries.append({'biome': biome_id, 'placement_type': placement, 'weight': weight})

    return entries


# ---------------------------------------------------------------------------
# Output grouping
# ---------------------------------------------------------------------------

def _depth_section(depth: list) -> str:
    lo, hi = depth[0], depth[1]
    if hi <= 0.05: return 'surface'
    if lo >= 1.05: return 'deep'
    if lo >= 0.15: return 'caves'
    return 'underground'


def _group_nether(entries: list[dict]) -> dict:
    sections: dict = {'surface': [], 'underground': [], 'caves': [], 'deep': []}
    seen: set = set()
    for e in entries:
        p = e['parameters']
        key = (e['biome'], tuple(p['temperature']), tuple(p['humidity']),
               tuple(p['continentalness']), tuple(p['erosion']),
               tuple(p['depth']), tuple(p['weirdness']), p['offset'])
        if key not in seen:
            seen.add(key)
            sections[_depth_section(p['depth'])].append(e)
    for v in sections.values():
        v.sort(key=lambda e: e['biome'])
    return sections


def _group_end(entries: list[dict]) -> dict:
    sections: dict = {'highlands': [], 'midlands': [], 'edge': [], 'islands': []}
    seen: set = set()
    for e in entries:
        key = (e['biome'], e['placement_type'])
        if key not in seen:
            seen.add(key)
            sections[e['placement_type']].append({'biome': e['biome'], 'weight': e['weight']})
    for v in sections.values():
        v.sort(key=lambda e: e['biome'])
    return sections


# ---------------------------------------------------------------------------
# Jar scanning
# ---------------------------------------------------------------------------

def scan_jar(jar_path: Path) -> tuple[str | None, list[dict], list[dict]]:
    """Returns (namespace, nether_entries, end_entries)."""
    nether: list[dict] = []
    end:    list[dict] = []

    try:
        with zipfile.ZipFile(jar_path) as z:
            ns = _jar_namespace(z)
            if ns in SKIP_NAMESPACES:
                return ns, [], []

            jar_ns = ns or 'unknown'
            classes = [n for n in z.namelist() if n.endswith('.class')]

            for class_name in classes:
                data = z.read(class_name)

                has_region = _B_REGION in data
                has_end    = _B_END_REG in data
                if not (has_region or has_end):
                    continue

                javap_text = _javap(data)

                if has_region and 'extends terrablender.api.Region' in javap_text:
                    if 'RegionType.NETHER' in javap_text or 'NETHER' in javap_text:
                        found, indirect = _parse_nether_region(javap_text, jar_ns)
                        if found:
                            nether.extend(found)
                            print(f'    {class_name}: {len(found)} nether biome(s)')
                        elif indirect:
                            print(f'    {class_name}: NETHER region uses helper methods '
                                  f'— add a dedicated parse_<mod>.py for this mod')

                if has_end:
                    found_end = _parse_end_registrations(javap_text, jar_ns)
                    if found_end:
                        end.extend(found_end)
                        print(f'    {class_name}: {len(found_end)} end registration(s)')

    except (zipfile.BadZipFile, Exception) as exc:
        print(f'  WARN: {jar_path.name}: {exc}')

    return ns, nether, end


# ---------------------------------------------------------------------------
# Output writing
# ---------------------------------------------------------------------------

def write_output(ns: str, nether_sections: dict, end_sections: dict) -> None:
    OUTPUT_DIR.mkdir(exist_ok=True)
    path = OUTPUT_DIR / f'{ns}.json'

    dims: dict = {}
    if path.exists():
        try:
            dims = json.loads(path.read_text(encoding='utf-8')).get('dimensions', {})
        except json.JSONDecodeError:
            pass

    changed = False
    if any(v for v in nether_sections.values()):
        dims['minecraft:nether'] = nether_sections
        ne_count = sum(len(v) for v in nether_sections.values())
        print(f'  {ns} nether: {ne_count} unique biome(s)')
        changed = True
    if any(v for v in end_sections.values()):
        dims['minecraft:the_end'] = end_sections
        en_count = sum(len(v) for v in end_sections.values())
        print(f'  {ns} end:    {en_count} unique biome(s)')
        changed = True

    if changed:
        path.write_text(json.dumps({'namespace': ns, 'dimensions': dims}, indent=2),
                        encoding='utf-8')
        print(f'  Written → {path}')


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    if not MODS_DIR.exists():
        print(f'ERROR: {MODS_DIR} not found.')
        raise SystemExit(1)

    jars = sorted(MODS_DIR.glob('*.jar'))
    print(f'Scanning {len(jars)} jar(s) in {MODS_DIR}/\n'
          f'  (skipping: {", ".join(sorted(SKIP_NAMESPACES))})\n')

    by_ns: dict[str, tuple[list, list]] = {}

    for jar_path in jars:
        ns, nether, end = scan_jar(jar_path)
        if not (nether or end):
            continue
        jar_ns = ns or jar_path.stem.split('-')[0].lower()
        if jar_ns in SKIP_NAMESPACES:
            continue
        print(f'{jar_path.name}  [{jar_ns}]')
        n_list, e_list = by_ns.setdefault(jar_ns, ([], []))
        n_list.extend(nether)
        e_list.extend(end)

    if not by_ns:
        print('No new TerraBlender nether/end biomes found (outside skip list).')
        return

    print()
    for ns in sorted(by_ns):
        nether, end = by_ns[ns]
        write_output(ns, _group_nether(nether), _group_end(end))

    print('\nRun merge_biome_parameters.py next.')


main()
