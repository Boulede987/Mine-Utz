"""
generate_structure_tags.py
==========================
Reads structure_planner.xlsx (✓ cells) and writes biome tag override JSONs
into the Paxi datapack so structures spawn in the chosen dimension biomes.

Tag format (replace=true → restricts structures to exactly the selected biomes):
    data/<ns>/tags/worldgen/biome/<path>.json

Structures that reference direct biomes (not a #tag) cannot be controlled
this way; they are skipped with a warning if you try to ✓ them.

Run after step 25 (generate_structure_planner.xlsx) and after editing planner.

Usage:
    python pythonScripts/worldgen/generate_structure_tags.py
"""

import json
import sys
import zipfile
from pathlib import Path

import openpyxl

sys.stdout.reconfigure(encoding='utf-8')

STRUCTURE_PLANNER  = Path('structure_planner.xlsx')
BIOME_PLANNER      = Path('dimension_biome_planner.xlsx')
BIOME_SHEET        = 'Biome Planner'
STRUCTURE_BIOMES   = Path('lists/structure_biomes.txt')
PAXI_DATA          = Path('minecraft/config/paxi/datapacks/mineutz/data')
MODS_DIR           = Path('minecraft/mods')
MANIFEST           = Path('worldgen_data/generated_structure_tags.json')
SUPPRESS_TAG       = '#mineutz:suppress'
OVERWORLD_SHEET    = 'Overworld'

CHECK_MARK = '✓'


# ---------------------------------------------------------------------------
# Parsers
# ---------------------------------------------------------------------------

def load_structure_tags() -> dict[str, list[str]]:
    """
    Returns {structure_id: [tag_ref, ...]} where tag_ref = 'namespace:path'
    (the leading '#' is stripped).  Structures with only direct-biome entries
    are NOT included.
    """
    result: dict[str, list[str]] = {}
    if not STRUCTURE_BIOMES.exists():
        print(f'ERROR: {STRUCTURE_BIOMES} not found.')
        raise SystemExit(1)

    for raw in STRUCTURE_BIOMES.read_text(encoding='utf-8').splitlines():
        line = raw.strip()
        if not line or '|' not in line or line.startswith('='):
            continue
        left, right = (p.strip() for p in line.split('|', 1))
        if not right.startswith('#'):
            continue
        tag = right[1:]           # strip '#'
        if ':' not in tag:
            continue              # malformed — no namespace
        result.setdefault(left, [])
        if tag not in result[left]:
            result[left].append(tag)
    return result


def load_dimension_biomes() -> dict[str, list[str]]:
    """
    Returns {dim_header: [biome_id, ...]} — the ordered list of biomes
    checked ✓ for each dimension column in the biome planner.
    Order matches the structure planner's column order.
    """
    if not BIOME_PLANNER.exists():
        print(f'ERROR: {BIOME_PLANNER} not found.')
        raise SystemExit(1)

    ws      = openpyxl.load_workbook(BIOME_PLANNER)[BIOME_SHEET]
    headers = [cell.value for cell in ws[1]]
    dim_cols = [(i, h) for i, h in enumerate(headers) if i >= 4 and h]

    result: dict[str, list[str]] = {}
    for col_idx, header in dim_cols:
        biomes = [
            str(row[0]) for row in ws.iter_rows(min_row=2, values_only=True)
            if row[0] and ':' in str(row[0]) and row[col_idx] == CHECK_MARK
        ]
        result[header] = biomes
    return result


def load_structure_selections(dim_biomes: dict[str, list[str]]) -> tuple[dict[str, list[str]], set[str], set[str], dict[str, list[str]]]:
    """
    Reads structure_planner.xlsx.
    Returns:
      selections          — {structure_id: [biome_id, ...]} for every ✓ cell (all sheets merged)
      seen                — set of all structure_ids present in the planner
      overworld_checked   — set of structure_ids with at least one ✓ in the Overworld sheet
      overworld_selections— {structure_id: [biome_id, ...]} ✓ cells from the Overworld sheet only
    """
    if not STRUCTURE_PLANNER.exists():
        print(f'ERROR: {STRUCTURE_PLANNER} not found.')
        raise SystemExit(1)

    wb   = openpyxl.load_workbook(STRUCTURE_PLANNER)
    result: dict[str, list[str]] = {}
    ow_result: dict[str, list[str]] = {}
    seen: set[str] = set()
    overworld_checked: set[str] = set()

    for sheet_name in wb.sheetnames:
        ws = wb[sheet_name]
        if sheet_name not in dim_biomes:
            print(f'  WARNING: sheet "{sheet_name}" not in biome planner — skip.')
            continue

        is_overworld = (sheet_name == OVERWORLD_SHEET)
        biome_ids = dim_biomes[sheet_name]

        for row in ws.iter_rows(min_row=2, values_only=True):
            structure_id = row[0]
            if not structure_id:
                continue
            seen.add(structure_id)

            for col_offset, biome_id in enumerate(biome_ids):
                col_val = row[2 + col_offset] if 2 + col_offset < len(row) else None
                if col_val == CHECK_MARK:
                    result.setdefault(structure_id, [])
                    if biome_id not in result[structure_id]:
                        result[structure_id].append(biome_id)
                    if is_overworld:
                        overworld_checked.add(structure_id)
                        ow_result.setdefault(structure_id, [])
                        if biome_id not in ow_result[structure_id]:
                            ow_result[structure_id].append(biome_id)

    return result, seen, overworld_checked, ow_result


# ---------------------------------------------------------------------------
# Structure JSON override (for shared-collection conflicts)
# ---------------------------------------------------------------------------

def _strip_comments(text: str) -> str:
    result, in_string, i = [], False, 0
    while i < len(text):
        c = text[i]
        if c == '"' and (i == 0 or text[i - 1] != '\\'):
            in_string = not in_string
        elif not in_string and c == '/' and i + 1 < len(text) and text[i + 1] == '/':
            while i < len(text) and text[i] != '\n':
                i += 1
            continue
        result.append(c)
        i += 1
    return ''.join(result)


def find_structure_json_in_jars(structure_id: str) -> dict | None:
    """Scan all JARs for data/<ns>/worldgen/structure/<path>.json and return parsed content."""
    if not MODS_DIR.exists():
        return None
    namespace, path = structure_id.split(':', 1)
    entry = f'data/{namespace}/worldgen/structure/{path}.json'
    for jar_path in sorted(MODS_DIR.glob('*.jar')):
        try:
            with zipfile.ZipFile(jar_path, 'r') as zf:
                if entry in zf.namelist():
                    raw = zf.read(entry).decode('utf-8', errors='ignore')
                    try:
                        return json.loads(raw)
                    except json.JSONDecodeError:
                        return json.loads(_strip_comments(raw))
        except Exception:
            continue
    return None


def write_structure_json_override(structure_id: str, written: list[str]) -> bool:
    """
    Write a Paxi structure JSON override that redirects 'biomes' to SUPPRESS_TAG.
    Returns True if written, False if structure JSON not found in any JAR.
    """
    data = find_structure_json_in_jars(structure_id)
    if data is None:
        return False
    data['biomes'] = SUPPRESS_TAG
    namespace, path = structure_id.split(':', 1)
    out = PAXI_DATA / namespace / 'worldgen' / 'structure' / (path + '.json')
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, indent='\t'), encoding='utf-8')
    written.append(str(out))
    return True


def write_controlled_override(structure_id: str, approved_biomes: list[str], written: list[str]) -> bool:
    """
    For a direct-biome structure that the user wants in specific overworld biomes:
    - Override the structure JSON to point 'biomes' at a custom mineutz tag.
    - Write that tag with the approved biome list.
    Returns True if written, False if structure JSON not found in any JAR.
    """
    data = find_structure_json_in_jars(structure_id)
    if data is None:
        return False

    namespace, path = structure_id.split(':', 1)
    tag_path   = f'overrides/{namespace}/{path}'       # e.g. overrides/nova_structures/firewatch_tower_forest
    tag_ref    = f'mineutz:{tag_path}'

    # Structure JSON override
    data['biomes'] = f'#{tag_ref}'
    struct_out = PAXI_DATA / namespace / 'worldgen' / 'structure' / (path + '.json')
    struct_out.parent.mkdir(parents=True, exist_ok=True)
    struct_out.write_text(json.dumps(data, indent='\t'), encoding='utf-8')
    written.append(str(struct_out))

    # Custom biome tag with approved biomes
    tag_out = PAXI_DATA / 'mineutz' / 'tags' / 'worldgen' / 'biome' / (tag_path + '.json')
    tag_out.parent.mkdir(parents=True, exist_ok=True)
    tag_out.write_text(json.dumps({'replace': True, 'values': approved_biomes}, indent=2), encoding='utf-8')
    written.append(str(tag_out))
    return True


def ensure_suppress_tag() -> None:
    """Ensure the empty #mineutz:suppress biome tag exists."""
    out = PAXI_DATA / 'mineutz' / 'tags' / 'worldgen' / 'biome' / 'suppress.json'
    if not out.exists():
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({'replace': True, 'values': []}, indent=2), encoding='utf-8')


# ---------------------------------------------------------------------------
# Tag file writer
# ---------------------------------------------------------------------------

def tag_ref_to_path(tag_ref: str) -> Path:
    """
    'minecraft:has_structure/village_plains'
    → PAXI_DATA / 'minecraft/tags/worldgen/biome/has_structure/village_plains.json'
    """
    namespace, path = tag_ref.split(':', 1)
    return PAXI_DATA / namespace / 'tags' / 'worldgen' / 'biome' / (path + '.json')


def load_manifest() -> list[str]:
    if MANIFEST.exists():
        return json.loads(MANIFEST.read_text(encoding='utf-8'))
    return []


def save_manifest(paths: list[str]) -> None:
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(sorted(paths), indent=2), encoding='utf-8')


def clean_previous(manifest: list[str]) -> None:
    removed = 0
    for p in manifest:
        f = Path(p)
        if f.exists():
            f.unlink()
            removed += 1
    if removed:
        print(f'  Cleaned {removed} old tag file(s).')


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    struct_tags                                    = load_structure_tags()
    dim_biomes                                     = load_dimension_biomes()
    selections, seen_all, ow_checked, ow_biomes    = load_structure_selections(dim_biomes)

    # Classify active (✓) structures:
    #   - No biome tag + ✓ in Overworld  → skipped_no_tag (will get controlled override)
    #   - No biome tag + ✓ only elsewhere → direct_suppressed (will get suppress override)
    #   - Has biome tag                   → group by tag_ref to detect sharing
    skipped_no_tag: list[str] = []
    direct_suppressed: list[str] = []
    # {tag_ref: [(structure_id, [biome_id, ...]), ...]}
    tag_to_active: dict[str, list[tuple[str, list[str]]]] = {}

    for structure_id, biome_ids in selections.items():
        if structure_id not in struct_tags:
            if structure_id in ow_checked:
                skipped_no_tag.append(structure_id)
            else:
                direct_suppressed.append(structure_id)
            continue
        for tag_ref in struct_tags[structure_id]:
            tag_to_active.setdefault(tag_ref, []).append((structure_id, biome_ids))

    # For each tag:
    #   - Used by exactly ONE active structure → write the tag directly (no overhead)
    #   - Shared by MULTIPLE active structures → each gets its own controlled override so
    #     biomes from one structure don't contaminate another
    tag_biomes: dict[str, list[str]] = {}          # tags written directly
    shared_active: list[tuple[str, list[str]]] = [] # (structure_id, biomes) needing controlled override

    for tag_ref, entries in tag_to_active.items():
        if len(entries) == 1:
            struct_id, biome_ids = entries[0]
            tag_biomes[tag_ref] = biome_ids
        else:
            for struct_id, biome_ids in entries:
                shared_active.append((struct_id, biome_ids))

    # Collect tags for all-✗ structures (in planner but zero ✓) → empty biome list
    # Skip tags already claimed by an active (✓) structure — shared collection tags cannot
    # be independently suppressed; the active structure wins and a warning is emitted.
    suppressed_tags: set[str] = set()
    suppressed_structs: list[str] = []
    shared_conflicts: list[tuple[str, str]] = []  # (structure_id, tag_ref)

    for structure_id in seen_all:
        if structure_id in selections:
            continue                       # has ≥1 ✓, handled above
        if structure_id not in struct_tags:
            direct_suppressed.append(structure_id)
            continue
        suppressed_structs.append(structure_id)
        for tag_ref in struct_tags[structure_id]:
            if tag_ref in tag_biomes or tag_ref in tag_to_active:
                # Tag is claimed by an active structure (exclusive or shared) — suppress via structure JSON override
                shared_conflicts.append((structure_id, tag_ref))
            else:
                suppressed_tags.add(tag_ref)

    # skipped_no_tag: ✓ in Overworld, no biome tag → convert to controlled tag via structure JSON override

    if shared_conflicts:
        print(f'  {len(shared_conflicts)} all-✗ structure(s) share a collection tag with an active '
              f'structure — writing Paxi structure JSON overrides to suppress individually:')

    if suppressed_structs:
        truly_suppressed = len(suppressed_tags)
        print(f'  Suppressing {truly_suppressed} tag(s) from all-✗ structure(s):')
        for s in suppressed_structs[:10]:
            print(f'    {s}')
        if len(suppressed_structs) > 10:
            print(f'    ... and {len(suppressed_structs) - 10} more.')

    # Clean old files
    manifest = load_manifest()
    clean_previous(manifest)

    # Ensure the shared suppress tag exists
    if shared_conflicts or direct_suppressed:
        ensure_suppress_tag()

    # Write tag files
    written: list[str] = []

    for tag_ref, biome_ids in sorted(tag_biomes.items()):
        out = tag_ref_to_path(tag_ref)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"replace": True, "values": biome_ids}, indent=2), encoding='utf-8')
        written.append(str(out))

    for tag_ref in sorted(suppressed_tags):
        out = tag_ref_to_path(tag_ref)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"replace": True, "values": []}, indent=2), encoding='utf-8')
        written.append(str(out))
        print(f'  SUPPRESS {tag_ref}')

    # Write Paxi structure JSON overrides for:
    #   (a) shared-collection conflicts — all-✗ but tag shared with active structure → suppress
    #   (b) direct-biome, all-✗ or ✓ only in non-Overworld → suppress
    #   (c) direct-biome, ✓ in Overworld → controlled override (custom tag with approved biomes)
    override_written = 0
    controlled_written = 0
    override_failed: list[str] = []
    seen_override_structs: set[str] = set()

    def _do_suppress_override(struct_id: str) -> None:
        nonlocal override_written
        if struct_id in seen_override_structs:
            return
        seen_override_structs.add(struct_id)
        if write_structure_json_override(struct_id, written):
            override_written += 1
            print(f'  OVERRIDE {struct_id}  (biomes → {SUPPRESS_TAG})')
        else:
            override_failed.append(struct_id)

    for struct_id, _tag_ref in shared_conflicts:
        _do_suppress_override(struct_id)

    for struct_id in direct_suppressed:
        _do_suppress_override(struct_id)

    # Active structures sharing a collection tag — each gets its own controlled override
    for struct_id, approved in shared_active:
        if struct_id in seen_override_structs:
            continue
        seen_override_structs.add(struct_id)
        if write_controlled_override(struct_id, approved, written):
            controlled_written += 1
            print(f'  CONTROLLED(shared) {struct_id}  ({len(approved)} biome(s))')
        else:
            override_failed.append(struct_id)

    for struct_id in skipped_no_tag:
        if struct_id in seen_override_structs:
            continue
        seen_override_structs.add(struct_id)
        approved = ow_biomes.get(struct_id, [])
        if write_controlled_override(struct_id, approved, written):
            controlled_written += 1
            print(f'  CONTROLLED {struct_id}  ({len(approved)} approved biome(s))')
        else:
            override_failed.append(struct_id)

    if override_failed:
        print(f'  WARNING: could not find structure JSON in JARs for {len(override_failed)} structure(s):')
        for s in override_failed:
            print(f'    {s}')

    save_manifest(written)

    if not written:
        print('No structures in planner — no tag files written.')
        return

    active = len(tag_biomes)
    suppressed = len(suppressed_tags)
    print(f'Wrote {len(written)} file(s): {active} active tag(s), {suppressed} suppressed tag(s), '
          f'{override_written} suppress override(s), {controlled_written} controlled override(s).')
    for tag_ref, biome_ids in sorted(tag_biomes.items()):
        print(f'  {tag_ref}  ({len(biome_ids)} biome(s))')


main()
