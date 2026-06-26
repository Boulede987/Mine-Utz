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
    BIOME_PLANNER_FIXED_COLUMN_COUNT, STRUCTURE_PLANNER_FIXED_COLUMN_COUNT,
)

sys.stdout.reconfigure(encoding='utf-8')

STRUCTURES_FILE    = Path('lists/structures.txt')
BIOME_PLANNER_FILE = Path('dimension_biome_planner.xlsx')
OUTPUT_FILE        = Path('structure_planner.xlsx')
BIOME_SHEET_NAME   = 'Biome Planner'

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

def load_structures() -> list[str]:
    ids = []
    for line in STRUCTURES_FILE.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('---') or line.startswith('====='):
            continue
        if ':' in line:
            ids.append(line)
    return ids


def load_dimension_biomes() -> list[tuple[str, list[str]]]:
    """Returns [(dim_header, [biome_id, ...]), ...] — biomes marked ✓ per dimension."""
    if not BIOME_PLANNER_FILE.exists():
        print(f'ERROR: {BIOME_PLANNER_FILE} not found.')
        raise SystemExit(1)

    ws      = openpyxl.load_workbook(BIOME_PLANNER_FILE)[BIOME_SHEET_NAME]
    headers = [cell.value for cell in ws[1]]
    dim_cols = [(i, h) for i, h in enumerate(headers) if i >= BIOME_PLANNER_FIXED_COLUMN_COUNT and h]

    result = []
    for col_idx, header in dim_cols:
        biomes = [
            row[0] for row in ws.iter_rows(min_row=2, values_only=True)
            if row[0] and ':' in str(row[0]) and row[col_idx] == CHECK_MARK
        ]
        if biomes:
            result.append((header, biomes))
    return result


def load_existing_selections(filepath: Path) -> dict[str, dict[str, dict[str, str]]]:
    """
    Read back user choices from an existing structure_planner.xlsx.
    Returns {sheet_name: {structure_id: {biome_short_name: '✓'/'✗'}}}.
    """
    if not filepath.exists():
        return {}
    wb = openpyxl.load_workbook(filepath, data_only=True)
    result: dict[str, dict[str, dict[str, str]]] = {}
    for sheet_name in wb.sheetnames:
        ws = wb[sheet_name]
        headers    = [cell.value for cell in ws[1]]
        biome_cols = headers[STRUCTURE_PLANNER_FIXED_COLUMN_COUNT:]
        sheet_data: dict[str, dict[str, str]] = {}
        for row in ws.iter_rows(min_row=2, values_only=True):
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


def build_sheet(wb: openpyxl.Workbook, sheet_name: str,
                biomes: list[str], structures: list[str], sheet_idx: int,
                existing: dict[str, dict[str, str]]) -> None:

    ws = wb.create_sheet(title=sheet_name)

    biome_labels = [b.split(':', 1)[1] if ':' in b else b for b in biomes]
    total_cols   = STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + len(biomes)
    last_row     = 1 + len(structures)

    # --- Header row ---
    for col, (label, fill, font) in enumerate([
        ('Structure', FIXED_HEADER_FILL, FIXED_HEADER_FONT),
        ('Source',    FIXED_HEADER_FILL, FIXED_HEADER_FONT),
    ], start=1):
        cell = ws.cell(row=1, column=col, value=label)
        cell.fill = fill; cell.font = font
        cell.alignment = CENTER; cell.border = BORDER

    for i, label in enumerate(biome_labels):
        col  = STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1 + i
        cell = ws.cell(row=1, column=col, value=label)
        cell.fill = BIOME_HEADER_FILL; cell.font = BIOME_HEADER_FONT
        cell.alignment = CENTER; cell.border = BORDER

    ws.row_dimensions[1].height = 80

    # --- Column widths ---
    ws.column_dimensions['A'].width = 48
    ws.column_dimensions['B'].width = 20
    for i in range(len(biomes)):
        ws.column_dimensions[get_column_letter(STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1 + i)].width = 14

    ws.freeze_panes = ws.cell(row=2, column=STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1)

    # --- Data rows ---
    for row_idx, structure_id in enumerate(structures, start=2):
        namespace = structure_id.split(':')[0]
        source    = NAMESPACE_TO_SOURCE.get(namespace, namespace)

        c = ws.cell(row=row_idx, column=1, value=structure_id)
        c.fill = ROW_FILL; c.font = ROW_FONT
        c.alignment = LEFT_WRAP; c.border = BORDER

        c = ws.cell(row=row_idx, column=2, value=source)
        c.fill = ROW_FILL; c.font = ROW_FONT
        c.alignment = CENTER; c.border = BORDER

        prev = existing.get(structure_id, {})
        for i, biome_label in enumerate(biome_labels):
            val  = prev.get(biome_label, CROSS_MARK)
            fill = CHECK_FILL if val == CHECK_MARK else CROSS_FILL
            font = CHECK_FONT if val == CHECK_MARK else CROSS_FONT
            c = ws.cell(row=row_idx, column=STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1 + i, value=val)
            c.fill = fill; c.font = font
            c.alignment = CENTER; c.border = BORDER

        ws.row_dimensions[row_idx].height = 16

    # --- Excel Table ---
    table_name = 'T_' + ''.join(c for c in sheet_name if c.isalnum())
    table_ref  = f'A1:{get_column_letter(total_cols)}{last_row}'
    table      = Table(displayName=table_name, ref=table_ref)
    table.tableStyleInfo = TableStyleInfo(
        name='TableStyleMedium2',
        showFirstColumn=False, showLastColumn=False,
        showRowStripes=False, showColumnStripes=False,
    )
    ws.add_table(table)

    # --- Data validation ---
    first_biome = get_column_letter(STRUCTURE_PLANNER_FIXED_COLUMN_COUNT + 1)
    last_biome  = get_column_letter(total_cols)
    dv = DataValidation(
        type='list', formula1='"✓,✗"', allow_blank=True,
        sqref=f'{first_biome}2:{last_biome}{last_row}',
    )
    ws.add_data_validation(dv)


def main() -> None:
    structures    = load_structures()
    dim_biomes    = load_dimension_biomes()
    existing_all  = load_existing_selections(OUTPUT_FILE)

    if not dim_biomes:
        print('No dimensions with ✓ biomes found in biome planner.')
        raise SystemExit(1)

    wb = openpyxl.Workbook()
    wb.remove(wb.active)   # remove default empty sheet

    for idx, (dim_header, biomes) in enumerate(dim_biomes):
        build_sheet(wb, dim_header, biomes, structures, idx, existing_all.get(dim_header, {}))
        print(f'  [{dim_header}]  {len(biomes)} biome columns')

    wb.save(OUTPUT_FILE)
    print(f'\nWrote {OUTPUT_FILE}  ({len(structures)} structures, {len(dim_biomes)} sheets)')


main()
