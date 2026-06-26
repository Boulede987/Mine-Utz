"""
generate_structure_planner.py
=============================
Generates structure_planner.xlsx with one sheet per dimension.

Each sheet:
  Row 1  : headers — Structure | Source | <biome1> | <biome2> | ...
           (biomes are those marked ✓ for that dimension in biome planner)
  Row 2+ : one row per structure — mark ✓/✗ per biome

✓ = green cell, ✗ = red cell. Headers are an Excel Table with filters.

Usage:
    python pythonScripts/worldgen/generate_structure_planner.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import openpyxl
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation
from openpyxl.worksheet.table import Table, TableStyleInfo

from worldgen.constants import (
    CHECK_MARK, CROSS_MARK, NAMESPACE_TO_SOURCE,
    STRUCTURE_PLANNER_FIXED_COLUMN_COUNT,
    STRUCTURE_PLANNER_FILE,
)
from worldgen.loaders import load_dimension_biomes

sys.stdout.reconfigure(encoding='utf-8')

STRUCTURES_FILE = Path('lists/structures.txt')

FIXED_HEADER_FILL = PatternFill('solid', fgColor='1F4E79')
FIXED_HEADER_FONT = Font(bold=True, color='FFFFFF')
BIOME_HEADER_FILL = PatternFill('solid', fgColor='2E75B6')
BIOME_HEADER_FONT = Font(bold=True, color='FFFFFF')
ROW_FILL          = PatternFill('solid', fgColor='FCE4D6')
ROW_FONT          = Font(color='000000')
CHECK_FILL        = PatternFill('solid', fgColor='00B050')
CHECK_FONT        = Font(bold=True, color='FFFFFF')
CROSS_FILL        = PatternFill('solid', fgColor='FF0000')
CROSS_FONT        = Font(bold=True, color='FFFFFF')

CENTER    = Alignment(horizontal='center', vertical='center', wrap_text=True)
LEFT_WRAP = Alignment(horizontal='left',   vertical='center', wrap_text=True)

THIN   = Side(style='thin', color='AAAAAA')
BORDER = Border(left=THIN, right=THIN, top=THIN, bottom=THIN)

STRUCTURE_COLUMN_WIDTH = 48
SOURCE_COLUMN_WIDTH    = 20
BIOME_COLUMN_WIDTH     = 14
HEADER_ROW_HEIGHT      = 80
DATA_ROW_HEIGHT        = 16

def load_structures() -> list[str]:
    ids = []
    for line in STRUCTURES_FILE.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('---') or line.startswith('====='):
            continue
        if ':' in line:
            ids.append(line)
    return ids


def load_existing_selections(filepath: Path) -> dict[str, dict[str, dict[str, str]]]:
    """
    Read back user choices from an existing structure_planner.xlsx.
    Returns {sheet_name: {structure_id: {biome_short_name: '✓'/'✗'}}}.
    """
    if not filepath.exists():
        return {}
    workbook = openpyxl.load_workbook(filepath, data_only=True)
    result: dict[str, dict[str, dict[str, str]]] = {}
    for sheet_name in workbook.sheetnames:
        worksheet = workbook[sheet_name]
        headers    = [cell.value for cell in worksheet[1]]
        biome_cols = headers[STRUCTURE_PLANNER_FIXED_COLUMN_COUNT:]
        sheet_data: dict[str, dict[str, str]] = {}
        for row in worksheet.iter_rows(min_row=2, values_only=True):
            sid = row[0]
            if not sid or ':' not in str(sid):
                continue
            choices: dict[str, str] = {}
            for i, bname in enumerate(biome_cols):
                if bname and (STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + i) < len(row):
                    choices[bname] = row[STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + i] or CROSS_MARK
            sheet_data[str(sid)] = choices
        result[sheet_name] = sheet_data
    return result


def _write_header_row(worksheet, biome_labels: list[str]) -> None:
    for col, (label, fill, font) in enumerate([
        ('Structure', FIXED_HEADER_FILL, FIXED_HEADER_FONT),
        ('Source',    FIXED_HEADER_FILL, FIXED_HEADER_FONT),
    ], start=1):
        cell = worksheet.cell(row=1, column=col, value=label)
        cell.fill = fill; cell.font = font
        cell.alignment = CENTER; cell.border = BORDER

    for i, label in enumerate(biome_labels):
        col  = STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1 + i
        cell = worksheet.cell(row=1, column=col, value=label)
        cell.fill = BIOME_HEADER_FILL; cell.font = BIOME_HEADER_FONT
        cell.alignment = CENTER; cell.border = BORDER

    worksheet.row_dimensions[1].height = HEADER_ROW_HEIGHT


def _apply_column_layout(worksheet, biome_count: int) -> None:
    worksheet.column_dimensions['A'].width = STRUCTURE_COLUMN_WIDTH
    worksheet.column_dimensions['B'].width = SOURCE_COLUMN_WIDTH
    for i in range(biome_count):
        worksheet.column_dimensions[get_column_letter(STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1 + i)].width = BIOME_COLUMN_WIDTH
    worksheet.freeze_panes = worksheet.cell(row=2, column=STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1)


def _write_data_rows(
    worksheet,
    structures: list[str],
    biome_labels: list[str],
    existing: dict[str, dict[str, str]],
) -> None:
    for row_idx, structure_id in enumerate(structures, start=2):
        namespace = structure_id.split(':')[0]
        source    = NAMESPACE_TO_SOURCE.get(namespace, namespace)

        cell = worksheet.cell(row=row_idx, column=1, value=structure_id)
        cell.fill = ROW_FILL; cell.font = ROW_FONT
        cell.alignment = LEFT_WRAP; cell.border = BORDER

        cell = worksheet.cell(row=row_idx, column=2, value=source)
        cell.fill = ROW_FILL; cell.font = ROW_FONT
        cell.alignment = CENTER; cell.border = BORDER

        prev = existing.get(structure_id, {})
        for i, biome_label in enumerate(biome_labels):
            val  = prev.get(biome_label, CROSS_MARK)
            fill = CHECK_FILL if val == CHECK_MARK else CROSS_FILL
            font = CHECK_FONT if val == CHECK_MARK else CROSS_FONT
            cell = worksheet.cell(row=row_idx, column=STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1 + i, value=val)
            cell.fill = fill; cell.font = font
            cell.alignment = CENTER; cell.border = BORDER

        worksheet.row_dimensions[row_idx].height = DATA_ROW_HEIGHT


def _add_excel_table(worksheet, sheet_name: str, total_cols: int, last_row: int) -> None:
    table_name = 'T_' + ''.join(char for char in sheet_name if char.isalnum())
    table_ref  = f'A1:{get_column_letter(total_cols)}{last_row}'
    table      = Table(displayName=table_name, ref=table_ref)
    table.tableStyleInfo = TableStyleInfo(
        name='TableStyleMedium2',
        showFirstColumn=False, showLastColumn=False,
        showRowStripes=False, showColumnStripes=False,
    )
    worksheet.add_table(table)


def _add_data_validation(worksheet, total_cols: int, last_row: int) -> None:
    first_biome = get_column_letter(STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1)
    last_biome  = get_column_letter(total_cols)
    data_validation = DataValidation(
        type='list', formula1='"✓,✗"', allow_blank=True,
        sqref=f'{first_biome}2:{last_biome}{last_row}',
    )
    worksheet.add_data_validation(data_validation)


def build_sheet(
    workbook: openpyxl.Workbook,
    sheet_name: str,
    biomes: list[str],
    structures: list[str],
    existing: dict[str, dict[str, str]],
) -> None:
    worksheet    = workbook.create_sheet(title=sheet_name)
    biome_labels = [b.split(':', 1)[1] if ':' in b else b for b in biomes]
    total_cols   = STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + len(biomes)
    last_row     = 1 + len(structures)

    _write_header_row(worksheet, biome_labels)
    _apply_column_layout(worksheet, len(biomes))
    _write_data_rows(worksheet, structures, biome_labels, existing)
    _add_excel_table(worksheet, sheet_name, total_cols, last_row)
    _add_data_validation(worksheet, total_cols, last_row)


def main() -> None:
    structures    = load_structures()
    dim_biomes    = {h: b for h, b in load_dimension_biomes().items() if b}
    existing_all  = load_existing_selections(STRUCTURE_PLANNER_FILE)

    if not dim_biomes:
        print('No dimensions with ✓ biomes found in biome planner.')
        raise SystemExit(1)

    workbook = openpyxl.Workbook()
    workbook.remove(workbook.active)   # remove default empty sheet

    for dim_header, biomes in dim_biomes.items():
        build_sheet(workbook, dim_header, biomes, structures, existing_all.get(dim_header, {}))
        print(f'  [{dim_header}]  {len(biomes)} biome columns')

    workbook.save(STRUCTURE_PLANNER_FILE)
    print(f'\nWrote {STRUCTURE_PLANNER_FILE}  ({len(structures)} structures, {len(dim_biomes)} sheets)')


main()
