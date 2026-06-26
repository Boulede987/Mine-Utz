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

sys.path.insert(0, str(Path(__file__).parent.parent))

import openpyxl

from worldgen.constants import (
    CHECK_MARK,
    STRUCTURE_PLANNER_FIXED_COLUMN_COUNT,
    STRUCTURE_PLANNER_FILE,
    OVERWORLD_PLANNER_HEADER,
)
from worldgen.loaders import load_dimension_biomes
from worldgen.structure_classifier import StructureClassification, classify_all_structures
from lib.io_utils import strip_comments

sys.stdout.reconfigure(encoding='utf-8')

STRUCTURE_BIOMES   = Path('lists/structure_biomes.txt')
PAXI_DATA          = Path('minecraft/config/paxi/datapacks/mineutz/data')
MODS_DIR           = Path('minecraft/mods')
MANIFEST           = Path('worldgen_data/generated_structure_tags.json')
SUPPRESS_TAG                      = '#mineutz:suppress'
CLASSIFICATION_SUMMARY_MAX_LISTED = 10


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

    for raw_line in STRUCTURE_BIOMES.read_text(encoding='utf-8').splitlines():
        line = raw_line.strip()
        if not line or '|' not in line or line.startswith('='):
            continue
        left, right = (part.strip() for part in line.split('|', 1))
        if not right.startswith('#'):
            continue
        tag = right[1:]
        if ':' not in tag:
            continue  # malformed — no namespace
        result.setdefault(left, [])
        if tag not in result[left]:
            result[left].append(tag)
    return result


def _process_structure_row(
    row,
    biome_ids: list[str],
    is_overworld: bool,
    seen_all: set[str],
    selections: dict[str, list[str]],
    overworld_checked: set[str],
    overworld_biomes_by_structure: dict[str, list[str]],
) -> None:
    structure_id = row[0]
    if not structure_id:
        return
    seen_all.add(structure_id)
    for col_offset, biome_id in enumerate(biome_ids):
        col_index = STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + col_offset
        col_val   = row[col_index] if col_index < len(row) else None
        if col_val != CHECK_MARK:
            continue
        if biome_id not in selections.setdefault(structure_id, []):
            selections[structure_id].append(biome_id)
        if is_overworld:
            overworld_checked.add(structure_id)
            if biome_id not in overworld_biomes_by_structure.setdefault(structure_id, []):
                overworld_biomes_by_structure[structure_id].append(biome_id)


def load_structure_selections(
    dim_biomes: dict[str, list[str]],
) -> tuple[dict[str, list[str]], set[str], set[str], dict[str, list[str]]]:
    """
    Reads structure_planner.xlsx.
    Returns:
      selections                    — {structure_id: [biome_id, ...]} for every ✓ cell (all sheets merged)
      seen_all                      — set of all structure_ids present in the planner
      overworld_checked             — set of structure_ids with at least one ✓ in the Overworld sheet
      overworld_biomes_by_structure — {structure_id: [biome_id, ...]} ✓ cells from the Overworld sheet only
    """
    if not STRUCTURE_PLANNER_FILE.exists():
        print(f'ERROR: {STRUCTURE_PLANNER_FILE} not found.')
        raise SystemExit(1)

    workbook = openpyxl.load_workbook(STRUCTURE_PLANNER_FILE)
    selections: dict[str, list[str]] = {}
    overworld_biomes_by_structure: dict[str, list[str]] = {}
    seen_all: set[str] = set()
    overworld_checked: set[str] = set()

    for sheet_name in workbook.sheetnames:
        worksheet = workbook[sheet_name]
        if sheet_name not in dim_biomes:
            print(f'  WARNING: sheet "{sheet_name}" not in biome planner — skip.')
            continue
        for row in worksheet.iter_rows(min_row=2, values_only=True):
            _process_structure_row(
                row, dim_biomes[sheet_name], sheet_name == OVERWORLD_PLANNER_HEADER,
                seen_all, selections, overworld_checked, overworld_biomes_by_structure,
            )

    return selections, seen_all, overworld_checked, overworld_biomes_by_structure


def _parse_json_lenient(raw: str) -> dict:
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return json.loads(strip_comments(raw))


def find_structure_json_in_jars(structure_id: str) -> dict | None:
    """Scan all JARs for data/<ns>/worldgen/structure/<path>.json and return parsed content."""
    if not MODS_DIR.exists():
        return None
    namespace, path = structure_id.split(':', 1)
    entry = f'data/{namespace}/worldgen/structure/{path}.json'
    for jar_path in sorted(MODS_DIR.glob('*.jar')):
        try:
            with zipfile.ZipFile(jar_path, 'r') as archive:
                if entry not in archive.namelist():
                    continue
                return _parse_json_lenient(archive.read(entry).decode('utf-8', errors='ignore'))
        except (zipfile.BadZipFile, OSError):
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
    output_path = PAXI_DATA / namespace / 'worldgen' / 'structure' / (path + '.json')
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(data, indent='\t'), encoding='utf-8')
    written.append(str(output_path))
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
    tag_path = f'overrides/{namespace}/{path}'
    tag_ref  = f'mineutz:{tag_path}'

    data['biomes'] = f'#{tag_ref}'
    struct_output = PAXI_DATA / namespace / 'worldgen' / 'structure' / (path + '.json')
    struct_output.parent.mkdir(parents=True, exist_ok=True)
    struct_output.write_text(json.dumps(data, indent='\t'), encoding='utf-8')
    written.append(str(struct_output))

    tag_output = PAXI_DATA / 'mineutz' / 'tags' / 'worldgen' / 'biome' / (tag_path + '.json')
    tag_output.parent.mkdir(parents=True, exist_ok=True)
    tag_output.write_text(json.dumps({'replace': True, 'values': approved_biomes}, indent=2), encoding='utf-8')
    written.append(str(tag_output))
    return True


def ensure_suppress_tag() -> None:
    """Ensure the empty #mineutz:suppress biome tag exists."""
    output_path = PAXI_DATA / 'mineutz' / 'tags' / 'worldgen' / 'biome' / 'suppress.json'
    if not output_path.exists():
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps({'replace': True, 'values': []}, indent=2), encoding='utf-8')


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
    for path_str in manifest:
        tag_file = Path(path_str)
        if tag_file.exists():
            tag_file.unlink()
            removed += 1
    if removed:
        print(f'  Cleaned {removed} old tag file(s).')


def _suppress_one_structure(
    structure_id: str,
    seen_overrides: set[str],
    override_failed: list[str],
    written: list[str],
) -> int:
    """Returns 1 if a new suppress override was written, 0 if already handled or JAR not found."""
    if structure_id in seen_overrides:
        return 0
    seen_overrides.add(structure_id)
    if write_structure_json_override(structure_id, written):
        print(f'  OVERRIDE {structure_id}  (biomes → {SUPPRESS_TAG})')
        return 1
    override_failed.append(structure_id)
    return 0


def _apply_one_controlled_override(
    structure_id: str,
    approved_biomes: list[str],
    label: str,
    seen_overrides: set[str],
    override_failed: list[str],
    written: list[str],
) -> int:
    """Returns 1 if a new controlled override was written, 0 if already handled or JAR not found."""
    if structure_id in seen_overrides:
        return 0
    seen_overrides.add(structure_id)
    if write_controlled_override(structure_id, approved_biomes, written):
        print(f'  {label} {structure_id}  ({len(approved_biomes)} biome(s))')
        return 1
    override_failed.append(structure_id)
    return 0


def write_all_active_tags(exclusive_tag_biomes: dict[str, list[str]], written: list[str]) -> None:
    for tag_ref, biome_ids in sorted(exclusive_tag_biomes.items()):
        output_path = tag_ref_to_path(tag_ref)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps({"replace": True, "values": biome_ids}, indent=2), encoding='utf-8')
        written.append(str(output_path))


def write_all_suppressed_tags(suppressed_tags: set[str], written: list[str]) -> None:
    for tag_ref in sorted(suppressed_tags):
        output_path = tag_ref_to_path(tag_ref)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps({"replace": True, "values": []}, indent=2), encoding='utf-8')
        written.append(str(output_path))
        print(f'  SUPPRESS {tag_ref}')


def write_all_suppress_overrides(
    shared_conflicts: list[tuple[str, str]],
    direct_suppressed: list[str],
    seen_overrides: set[str],
    override_failed: list[str],
    written: list[str],
) -> int:
    override_count = 0
    for structure_id, _ in shared_conflicts:
        override_count += _suppress_one_structure(structure_id, seen_overrides, override_failed, written)
    for structure_id in direct_suppressed:
        override_count += _suppress_one_structure(structure_id, seen_overrides, override_failed, written)
    return override_count


def write_all_controlled_overrides(
    shared_active_structs: list[tuple[str, list[str]]],
    skipped_no_tag: list[str],
    overworld_biomes_by_structure: dict[str, list[str]],
    seen_overrides: set[str],
    override_failed: list[str],
    written: list[str],
) -> int:
    controlled_count = 0
    for structure_id, approved_biomes in shared_active_structs:
        controlled_count += _apply_one_controlled_override(
            structure_id, approved_biomes, 'CONTROLLED(shared)',
            seen_overrides, override_failed, written,
        )
    for structure_id in skipped_no_tag:
        approved_biomes = overworld_biomes_by_structure.get(structure_id, [])
        controlled_count += _apply_one_controlled_override(
            structure_id, approved_biomes, 'CONTROLLED',
            seen_overrides, override_failed, written,
        )
    return controlled_count


def print_classification_summary(classification: StructureClassification) -> None:
    if classification.shared_conflicts:
        print(f'  {len(classification.shared_conflicts)} all-✗ structure(s) share a collection tag with an active '
              f'structure — writing Paxi structure JSON overrides to suppress individually:')
    if classification.suppressed_structs:
        print(f'  Suppressing {len(classification.suppressed_tags)} tag(s) from all-✗ structure(s):')
        for structure_id in classification.suppressed_structs[:CLASSIFICATION_SUMMARY_MAX_LISTED]:
            print(f'    {structure_id}')
        if len(classification.suppressed_structs) > CLASSIFICATION_SUMMARY_MAX_LISTED:
            print(f'    ... and {len(classification.suppressed_structs) - CLASSIFICATION_SUMMARY_MAX_LISTED} more.')


def print_override_failures(override_failed: list[str]) -> None:
    if not override_failed:
        return
    print(f'  WARNING: could not find structure JSON in JARs for {len(override_failed)} structure(s):')
    for structure_id in override_failed:
        print(f'    {structure_id}')


def print_final_summary(
    written: list[str],
    classification: StructureClassification,
    suppress_override_count: int,
    controlled_override_count: int,
) -> None:
    if not written:
        print('No structures in planner — no tag files written.')
        return
    active_count     = len(classification.exclusive_tag_biomes)
    suppressed_count = len(classification.suppressed_tags)
    print(
        f'Wrote {len(written)} file(s): {active_count} active tag(s), {suppressed_count} suppressed tag(s), '
        f'{suppress_override_count} suppress override(s), {controlled_override_count} controlled override(s).'
    )
    for tag_ref, biome_ids in sorted(classification.exclusive_tag_biomes.items()):
        print(f'  {tag_ref}  ({len(biome_ids)} biome(s))')


def main() -> None:
    struct_tags  = load_structure_tags()
    dim_biomes   = load_dimension_biomes()
    selections, seen_all, overworld_checked_structures, overworld_biomes_by_structure = (
        load_structure_selections(dim_biomes)
    )

    classification = classify_all_structures(struct_tags, selections, seen_all, overworld_checked_structures)
    print_classification_summary(classification)

    clean_previous(load_manifest())

    if classification.shared_conflicts or classification.direct_suppressed:
        ensure_suppress_tag()

    written: list[str] = []
    write_all_active_tags(classification.exclusive_tag_biomes, written)
    write_all_suppressed_tags(classification.suppressed_tags, written)

    seen_overrides: set[str] = set()
    override_failed: list[str] = []
    suppress_override_count = write_all_suppress_overrides(
        classification.shared_conflicts,
        classification.direct_suppressed,
        seen_overrides, override_failed, written,
    )
    controlled_override_count = write_all_controlled_overrides(
        classification.shared_active_structs,
        classification.skipped_no_tag,
        overworld_biomes_by_structure,
        seen_overrides, override_failed, written,
    )

    print_override_failures(override_failed)
    save_manifest(written)
    print_final_summary(written, classification, suppress_override_count, controlled_override_count)


main()
