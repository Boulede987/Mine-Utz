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

import openpyxl
from openpyxl.styles import Font
from openpyxl.worksheet.datavalidation import DataValidation

sys.stdout.reconfigure(encoding='utf-8')

MERGED_DATA_DIR = Path('worldgen_data') / 'merged'
PLANNER_FILE    = Path('dimension_biome_planner.xlsx')
SHEET_NAME      = 'Biome Planner'
CROSS_MARK      = '✗'
ALL_SECTIONS    = ['surface', 'underground', 'caves', 'deep']

NAMESPACE_TO_SOURCE = {
    'biomeswevegone':                 'BWG',
    'biomesoplenty':                  'BoP',
    'shineals_prehistoric_expansion': 'Shineals',
    'minecraft':                      'Vanilla',
    'undergroundworlds':              'Underground Worlds',
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


def load_dimension_column_count(worksheet) -> int:
    headers = [cell.value for cell in worksheet[1]]
    return len(headers) - 4


def load_all_biomes() -> list[dict]:
    """One entry per unique biome across all sections and namespace files."""
    if not MERGED_DATA_DIR.exists():
        print('ERROR: worldgen_data/merged/ not found. Run merge_biome_parameters.py first.')
        raise SystemExit(1)

    seen: set[str] = set()
    biomes = []
    for json_file in sorted(MERGED_DATA_DIR.glob('*.json')):
        data      = json.loads(json_file.read_text(encoding='utf-8'))
        namespace = data['namespace']
        for dimension_sections in data.get('dimensions', {}).values():
            for section_name in ALL_SECTIONS:
                for entry in dimension_sections.get(section_name, []):
                    biome_id = entry['biome']
                    if biome_id not in seen:
                        seen.add(biome_id)
                        biomes.append({
                            'biome':      biome_id,
                            'namespace':  namespace,
                            'parameters': entry['parameters'],
                        })
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
    dimension_start_column = 5
    dimension_end_column   = 4 + dimension_column_count
    for row in worksheet.iter_rows(min_row=2, max_row=last_data_row,
                                   min_col=dimension_start_column,
                                   max_col=dimension_end_column):
        for cell in row:
            if cell.value == '✓':
                cell.font = Font(color=CHECK_MARK_COLOR, bold=cell.font.bold if cell.font else False)
            elif cell.value == '✗':
                cell.font = Font(color=CROSS_MARK_COLOR, bold=cell.font.bold if cell.font else False)


def extend_data_validation(worksheet, new_last_data_row: int, dimension_column_count: int) -> None:
    dimension_column_end = chr(ord('E') + dimension_column_count - 1)
    new_sqref = f'E2:{dimension_column_end}{new_last_data_row}'
    for data_validation in worksheet.data_validations.dataValidation:
        if data_validation.type == 'list' and '✓' in (data_validation.formula1 or ''):
            data_validation.sqref = new_sqref
            return
    worksheet.add_data_validation(DataValidation(
        type='list', formula1='"✓,✗"', allow_blank=True, sqref=new_sqref,
    ))


def main() -> None:
    if not PLANNER_FILE.exists():
        print(f'ERROR: {PLANNER_FILE} not found.')
        raise SystemExit(1)

    workbook  = openpyxl.load_workbook(PLANNER_FILE)
    worksheet = workbook[SHEET_NAME]

    primary_last_row       = find_primary_table_last_row(worksheet)
    misplaced_row_numbers  = find_misplaced_rows(worksheet, primary_last_row)
    primary_biome_ids      = load_primary_table_biome_ids(worksheet, primary_last_row)
    dimension_column_count = load_dimension_column_count(worksheet)
    all_biomes             = load_all_biomes()

    if misplaced_row_numbers:
        print(f'Removing {len(misplaced_row_numbers)} misplaced row(s) from previous run(s)...')
        for row_number in sorted(misplaced_row_numbers, reverse=True):
            worksheet.delete_rows(row_number, 1)

    missing_biomes = [
        entry for entry in all_biomes
        if entry['biome'] not in primary_biome_ids
    ]

    if not missing_biomes:
        print('No missing biomes — colorizing existing rows only.')
        colorize_checkmarks(worksheet, primary_last_row, dimension_column_count)
        workbook.save(PLANNER_FILE)
        return

    missing_biomes.sort(key=lambda entry: (entry['namespace'], entry['biome']))

    insert_at = primary_last_row + 1
    worksheet.insert_rows(insert_at, amount=len(missing_biomes))

    template_row = list(worksheet.iter_rows(min_row=primary_last_row, max_row=primary_last_row))[0]

    for index, biome_entry in enumerate(missing_biomes):
        target_row_number = insert_at + index
        namespace  = biome_entry['namespace']
        parameters = biome_entry['parameters']
        row_values = [
            biome_entry['biome'],
            NAMESPACE_TO_SOURCE.get(namespace, namespace),
            temperature_label(parameters['temperature']),
            humidity_label(parameters['humidity']),
        ] + [CROSS_MARK] * dimension_column_count

        for column_index, value in enumerate(row_values, start=1):
            worksheet.cell(row=target_row_number, column=column_index, value=value)

        target_row = list(worksheet.iter_rows(min_row=target_row_number, max_row=target_row_number))[0]
        copy_row_format(template_row, target_row)
        print(f'  Added: {biome_entry["biome"]} ({NAMESPACE_TO_SOURCE.get(namespace, namespace)})')

    new_last_data_row = insert_at + len(missing_biomes) - 1
    extend_data_validation(worksheet, new_last_data_row, dimension_column_count)
    colorize_checkmarks(worksheet, new_last_data_row, dimension_column_count)

    workbook.save(PLANNER_FILE)
    print(f'\nAdded {len(missing_biomes)} biome(s) to {PLANNER_FILE}')


main()
