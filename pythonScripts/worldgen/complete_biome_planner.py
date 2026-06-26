"""
complete_biome_planner.py
=========================
Reads worldgen_data/merged/*.json (all sections: surface, caves, underground, deep)
and appends any biomes missing from dimension_biome_planner.xlsx.

Handles cleanup of misplaced rows from previous bad runs (no fill, after the table).
New rows are inserted directly after the last formatted table row, with:
  - Fill and font copied from that row
  - ✓/✗ data validation extended to cover new rows

Existing rows and user choices are untouched.

Requires: pip install openpyxl
Usage:
    python pythonScripts/worldgen/complete_biome_planner.py
"""

import json
import sys
from copy import copy
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import openpyxl
from openpyxl.styles import Font
from openpyxl.worksheet.datavalidation import DataValidation

from worldgen.constants import CHECK_MARK, CROSS_MARK, NAMESPACE_TO_SOURCE, BIOME_PLANNER_FIXED_COLUMN_COUNT

sys.stdout.reconfigure(encoding='utf-8')

MERGED_DATA_DIR = Path('worldgen_data') / 'merged'
PLANNER_FILE    = Path('dimension_biome_planner.xlsx')
SHEET_NAME      = 'Biome Planner'
ALL_SECTIONS    = ['surface', 'underground', 'caves', 'deep']
END_SECTIONS    = ['highlands', 'midlands', 'edge', 'islands']

FIRST_DIMENSION_COLUMN_LETTER = chr(ord('A') + BIOME_PLANNER_FIXED_COLUMN_COUNT)

REQUIRED_DIMENSION_COLUMNS = ['Nether', 'End']

# When auto-adding a column, biomes found under this dimension key get ✓ by default.
COLUMN_DIMENSION_AFFINITY = {
    'Nether': 'minecraft:nether',
    'End':    'minecraft:the_end',
}

def temperature_label(temperature_range: list[float]) -> str:
    midpoint = (temperature_range[0] + temperature_range[1]) / 2
    if midpoint < -0.45: return 'Frozen'
    if midpoint < -0.15: return 'Cold'
    if midpoint < 0.2:   return 'Neutral'
    if midpoint < 0.55:  return 'Warm'
    return 'Hot'


def humidity_label(humidity_range: list[float]) -> str:
    midpoint = (humidity_range[0] + humidity_range[1]) / 2
    if midpoint < -0.35: return 'Arid'
    if midpoint < -0.1:  return 'Dry'
    if midpoint < 0.1:   return 'Moderate'
    if midpoint < 0.3:   return 'Humid'
    return 'Wet'


def is_biome_row(cell_value) -> bool:
    return bool(cell_value and ':' in str(cell_value))


def has_table_fill(cell) -> bool:
    return cell.fill and cell.fill.fgColor.rgb != '00000000'


def find_primary_table_last_row(worksheet) -> int:
    """Last row that has both a biome ID and a fill color — the real end of the table."""
    last_formatted_row = 1
    for row in worksheet.iter_rows(min_row=2):
        cell = row[0]
        if is_biome_row(cell.value) and has_table_fill(cell):
            last_formatted_row = cell.row
    return last_formatted_row


def find_misplaced_rows(worksheet, primary_last_row: int) -> list[int]:
    """Biome rows after primary_last_row — from previous bad script runs."""
    misplaced = []
    for row in worksheet.iter_rows(min_row=primary_last_row + 1):
        if is_biome_row(row[0].value):
            misplaced.append(row[0].row)
    return misplaced


def load_primary_table_biome_ids(worksheet, primary_last_row: int) -> set[str]:
    existing = set()
    for row in worksheet.iter_rows(min_row=2, max_row=primary_last_row, values_only=True):
        if is_biome_row(row[0]):
            existing.add(row[0])
    return existing


def load_affinity_biomes() -> dict[str, set[str]]:
    """Returns {dimension_id: {biome_id, ...}} from merged data."""
    result: dict[str, set[str]] = {}
    if not MERGED_DATA_DIR.exists():
        return result
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data = json.loads(json_file.read_text(encoding='utf-8'))
        if not isinstance(data, dict) or 'dimensions' not in data:
            continue
        for dim_id, sections in data['dimensions'].items():
            if dim_id not in result:
                result[dim_id] = set()
            for section_name, entries in sections.items():
                for entry in entries:
                    result[dim_id].add(entry['biome'])
    return result


def load_dimension_column_count(worksheet) -> int:
    headers = [cell.value for cell in worksheet[1]]
    return len(headers) - BIOME_PLANNER_FIXED_COLUMN_COUNT


def ensure_dimension_columns(worksheet, last_data_row: int,
                              affinity: dict[str, set[str]]) -> None:
    """Add any REQUIRED_DIMENSION_COLUMNS that are missing from the header."""
    headers = [cell.value for cell in worksheet[1]]
    present = set(headers)

    for dim_name in REQUIRED_DIMENSION_COLUMNS:
        if dim_name in present:
            continue

        new_col   = len(headers) + 1
        headers.append(dim_name)
        dim_biome_set = affinity.get(COLUMN_DIMENSION_AFFINITY.get(dim_name, ''), set())

        template_header = next(
            (worksheet.cell(row=1, column=i + 1) for i, h in enumerate(worksheet[1]) if i >= BIOME_PLANNER_FIXED_COLUMN_COUNT and h.value),
            None,
        )
        h_cell = worksheet.cell(row=1, column=new_col, value=dim_name)
        if template_header and template_header.has_style:
            h_cell.fill      = copy(template_header.fill)
            h_cell.font      = copy(template_header.font)
            h_cell.border    = copy(template_header.border)
            h_cell.alignment = copy(template_header.alignment)

        for row_idx in range(2, last_data_row + 1):
            biome_id  = worksheet.cell(row=row_idx, column=1).value
            val       = CHECK_MARK if biome_id in dim_biome_set else CROSS_MARK
            template_data = worksheet.cell(row=row_idx, column=new_col - 1)
            d_cell = worksheet.cell(row=row_idx, column=new_col, value=val)
            if template_data.has_style:
                d_cell.fill      = copy(template_data.fill)
                d_cell.font      = copy(template_data.font)
                d_cell.border    = copy(template_data.border)
                d_cell.alignment = copy(template_data.alignment)

        print(f'  Added dimension column: {dim_name}')


def apply_affinity_defaults(worksheet, last_data_row: int,
                             affinity: dict[str, set[str]]) -> None:
    """
    For columns that have a COLUMN_DIMENSION_AFFINITY mapping, set ✓ for any
    biome row that is currently ✗ but belongs to that dimension in merged data.
    Used as a repair pass when the column already exists but was created before
    affinity logic was added.
    """
    headers = [cell.value for cell in worksheet[1]]
    repaired = 0
    for col_idx, col_name in enumerate(headers):
        if col_name not in COLUMN_DIMENSION_AFFINITY:
            continue
        dim_biome_set = affinity.get(COLUMN_DIMENSION_AFFINITY[col_name], set())
        col_num = col_idx + 1
        for row_idx in range(2, last_data_row + 1):
            biome_cell = worksheet.cell(row=row_idx, column=1)
            val_cell   = worksheet.cell(row=row_idx, column=col_num)
            if biome_cell.value in dim_biome_set and val_cell.value != CHECK_MARK:
                val_cell.value = CHECK_MARK
                repaired += 1
    if repaired:
        print(f'  Applied affinity defaults: {repaired} cell(s) set to ✓')


def _add_biome_entry_if_new(
    biome_id: str, namespace: str, parameters, seen: set[str], biomes: list[dict],
) -> None:
    if biome_id not in seen:
        seen.add(biome_id)
        biomes.append({'biome': biome_id, 'namespace': namespace, 'parameters': parameters})


def _collect_biomes_from_file(data: dict, seen: set[str], biomes: list[dict]) -> None:
    namespace = data['namespace']
    for dimension_sections in data.get('dimensions', {}).values():
        for section_name in ALL_SECTIONS:
            for entry in dimension_sections.get(section_name, []):
                _add_biome_entry_if_new(entry['biome'], namespace, entry['parameters'], seen, biomes)
        for section_name in END_SECTIONS:
            for entry in dimension_sections.get(section_name, []):
                _add_biome_entry_if_new(entry['biome'], namespace, None, seen, biomes)


def load_all_biomes() -> list[dict]:
    """One entry per unique biome across all sections and namespace files."""
    if not MERGED_DATA_DIR.exists():
        print('ERROR: worldgen_data/merged/ not found. Run merge_biome_parameters.py first.')
        raise SystemExit(1)

    seen: set[str] = set()
    biomes = []
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        _collect_biomes_from_file(json.loads(json_file.read_text(encoding='utf-8')), seen, biomes)
    return biomes


def copy_row_format(source_row, target_row) -> None:
    for source_cell, target_cell in zip(source_row, target_row):
        if source_cell.has_style:
            target_cell.font          = copy(source_cell.font)
            target_cell.fill          = copy(source_cell.fill)
            target_cell.border        = copy(source_cell.border)
            target_cell.alignment     = copy(source_cell.alignment)
            target_cell.number_format = source_cell.number_format


CHECK_MARK_COLOR = '00B050'
CROSS_MARK_COLOR = 'FF0000'


def colorize_checkmarks(worksheet, last_data_row: int, dimension_column_count: int) -> None:
    """Set ✓ cells green and ✗ cells red across all data rows."""
    dimension_start_column = BIOME_PLANNER_FIXED_COLUMN_COUNT + 1
    dimension_end_column   = BIOME_PLANNER_FIXED_COLUMN_COUNT + dimension_column_count
    for row in worksheet.iter_rows(min_row=2, max_row=last_data_row,
                                   min_col=dimension_start_column,
                                   max_col=dimension_end_column):
        for cell in row:
            if cell.value == CHECK_MARK:
                cell.font = Font(color=CHECK_MARK_COLOR, bold=cell.font.bold if cell.font else False)
            elif cell.value == CROSS_MARK:
                cell.font = Font(color=CROSS_MARK_COLOR, bold=cell.font.bold if cell.font else False)


def extend_data_validation(worksheet, new_last_data_row: int, dimension_column_count: int) -> None:
    dimension_column_end = chr(ord(FIRST_DIMENSION_COLUMN_LETTER) + dimension_column_count - 1)
    new_sqref = f'{FIRST_DIMENSION_COLUMN_LETTER}2:{dimension_column_end}{new_last_data_row}'
    for data_validation in worksheet.data_validations.dataValidation:
        if data_validation.type == 'list' and '✓' in (data_validation.formula1 or ''):
            data_validation.sqref = new_sqref
            return
    worksheet.add_data_validation(DataValidation(
        type='list', formula1='"✓,✗"', allow_blank=True, sqref=new_sqref,
    ))


def _insert_biome_row(
    worksheet, template_row, row_number: int, biome_entry: dict, dimension_column_count: int,
) -> None:
    namespace  = biome_entry['namespace']
    parameters = biome_entry['parameters']
    temp_lbl   = temperature_label(parameters['temperature']) if parameters else 'N/A'
    hum_lbl    = humidity_label(parameters['humidity'])       if parameters else 'N/A'
    row_values = [
        biome_entry['biome'],
        NAMESPACE_TO_SOURCE.get(namespace, namespace),
        temp_lbl,
        hum_lbl,
    ] + [CROSS_MARK] * dimension_column_count

    for column_index, value in enumerate(row_values, start=1):
        worksheet.cell(row=row_number, column=column_index, value=value)

    target_row = list(worksheet.iter_rows(min_row=row_number, max_row=row_number))[0]
    copy_row_format(template_row, target_row)
    print(f'  Added: {biome_entry["biome"]} ({NAMESPACE_TO_SOURCE.get(namespace, namespace)})')


def main() -> None:
    if not PLANNER_FILE.exists():
        print(f'ERROR: {PLANNER_FILE} not found.')
        raise SystemExit(1)

    workbook  = openpyxl.load_workbook(PLANNER_FILE)
    worksheet = workbook[SHEET_NAME]

    primary_last_row       = find_primary_table_last_row(worksheet)
    misplaced_row_numbers  = find_misplaced_rows(worksheet, primary_last_row)
    primary_biome_ids      = load_primary_table_biome_ids(worksheet, primary_last_row)
    affinity               = load_affinity_biomes()
    ensure_dimension_columns(worksheet, primary_last_row, affinity)
    apply_affinity_defaults(worksheet, primary_last_row, affinity)
    dimension_column_count = load_dimension_column_count(worksheet)
    all_biomes             = load_all_biomes()

    if misplaced_row_numbers:
        print(f'Removing {len(misplaced_row_numbers)} misplaced row(s) from previous run(s)...')
        for row_number in sorted(misplaced_row_numbers, reverse=True):
            worksheet.delete_rows(row_number, 1)

    missing_biomes = [entry for entry in all_biomes if entry['biome'] not in primary_biome_ids]

    if not missing_biomes:
        print('No missing biomes — colorizing existing rows only.')
        colorize_checkmarks(worksheet, primary_last_row, dimension_column_count)
        workbook.save(PLANNER_FILE)
        return

    missing_biomes.sort(key=lambda entry: (entry['namespace'], entry['biome']))

    insert_at    = primary_last_row + 1
    template_row = list(worksheet.iter_rows(min_row=primary_last_row, max_row=primary_last_row))[0]
    worksheet.insert_rows(insert_at, amount=len(missing_biomes))

    for index, biome_entry in enumerate(missing_biomes):
        _insert_biome_row(worksheet, template_row, insert_at + index, biome_entry, dimension_column_count)

    new_last_data_row = insert_at + len(missing_biomes) - 1
    extend_data_validation(worksheet, new_last_data_row, dimension_column_count)
    colorize_checkmarks(worksheet, new_last_data_row, dimension_column_count)

    workbook.save(PLANNER_FILE)
    print(f'\nAdded {len(missing_biomes)} biome(s) to {PLANNER_FILE}')


main()
