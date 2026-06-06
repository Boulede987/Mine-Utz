"""
generate_structure_tags.py
==========================
Reads structure_planner.xlsx (✓ cells) and writes biome tag override JSONs
into the Paxi datapack so structures spawn in the chosen dimension biomes.

Tag format (replace=false → additive, won't remove vanilla spawn biomes):
    data/<ns>/tags/worldgen/biome/<path>.json

Structures that reference direct biomes (not a #tag) cannot be controlled
this way; they are skipped with a warning if you try to ✓ them.

Run after step 25 (generate_structure_planner.xlsx) and after editing planner.

Usage:
    python pythonScripts/worldgen/generate_structure_tags.py
"""

import json
import sys
from pathlib import Path

import openpyxl

sys.stdout.reconfigure(encoding='utf-8')

STRUCTURE_PLANNER  = Path('structure_planner.xlsx')
BIOME_PLANNER      = Path('dimension_biome_planner.xlsx')
BIOME_SHEET        = 'Biome Planner'
STRUCTURE_BIOMES   = Path('lists/structure_biomes.txt')
PAXI_DATA          = Path('minecraft/config/paxi/datapacks/mineutz/data')
MANIFEST           = Path('worldgen_data/generated_structure_tags.json')

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


def load_structure_selections(dim_biomes: dict[str, list[str]]) -> dict[str, list[str]]:
    """
    Reads structure_planner.xlsx.
    Returns {structure_id: [biome_id, ...]} for every ✓ cell.

    Biome columns (3+) map to the ordered biome list from the biome planner
    for the matching dimension sheet.
    """
    if not STRUCTURE_PLANNER.exists():
        print(f'ERROR: {STRUCTURE_PLANNER} not found.')
        raise SystemExit(1)

    wb = openpyxl.load_workbook(STRUCTURE_PLANNER)
    result: dict[str, list[str]] = {}

    for sheet_name in wb.sheetnames:
        ws = wb[sheet_name]
        if sheet_name not in dim_biomes:
            print(f'  WARNING: sheet "{sheet_name}" not in biome planner — skip.')
            continue

        biome_ids = dim_biomes[sheet_name]   # ordered list, matches column 3, 4, ...

        for row in ws.iter_rows(min_row=2, values_only=True):
            structure_id = row[0]
            if not structure_id:
                continue

            for col_offset, biome_id in enumerate(biome_ids):
                col_val = row[2 + col_offset] if 2 + col_offset < len(row) else None
                if col_val == CHECK_MARK:
                    result.setdefault(structure_id, [])
                    if biome_id not in result[structure_id]:
                        result[structure_id].append(biome_id)

    return result


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
    struct_tags = load_structure_tags()
    dim_biomes  = load_dimension_biomes()
    selections  = load_structure_selections(dim_biomes)

    # Build {tag_ref: set(biome_id)}
    tag_biomes: dict[str, list[str]] = {}
    skipped_no_tag: list[str] = []

    for structure_id, biome_ids in selections.items():
        if structure_id not in struct_tags:
            skipped_no_tag.append(structure_id)
            continue
        for tag_ref in struct_tags[structure_id]:
            tag_biomes.setdefault(tag_ref, [])
            for b in biome_ids:
                if b not in tag_biomes[tag_ref]:
                    tag_biomes[tag_ref].append(b)

    if skipped_no_tag:
        print(f'  WARNING: {len(skipped_no_tag)} structure(s) marked ✓ but use direct biomes '
              f'(no tag override possible) — skipped:')
        for s in skipped_no_tag[:10]:
            print(f'    {s}')
        if len(skipped_no_tag) > 10:
            print(f'    ... and {len(skipped_no_tag) - 10} more.')

    # Clean old files
    manifest = load_manifest()
    clean_previous(manifest)

    if not tag_biomes:
        print('No ✓ selections found — no tag files written.')
        save_manifest([])
        return

    # Write tag files
    written: list[str] = []
    for tag_ref, biome_ids in sorted(tag_biomes.items()):
        out = tag_ref_to_path(tag_ref)
        out.parent.mkdir(parents=True, exist_ok=True)
        payload = {"replace": False, "values": biome_ids}
        out.write_text(json.dumps(payload, indent=2), encoding='utf-8')
        written.append(str(out))

    save_manifest(written)

    print(f'Wrote {len(written)} tag file(s) across {len(set(selections.keys()))} structure(s).')
    for tag_ref, biome_ids in sorted(tag_biomes.items()):
        print(f'  {tag_ref}  ({len(biome_ids)} biome(s))')


main()
