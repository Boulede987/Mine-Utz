"""
generate_biome_adjacency_planner.py
====================================
Generates biome_adjency_planner.xlsx with, per dimension:
  - a biome list sheet     (Biome | Source | Temperature | Humidity)
  - an adjacency matrix sheet (NxN grid of every biome in that dimension
    against itself) — ✓ means the two biomes are allowed to spawn next
    to each other.

Adjacency is symmetric, so only the half above the diagonal is
editable (✓/✗ dropdown); the half below mirrors it live via formula
and is greyed out. The last column (Degree) counts a row's ✓ total.

Existing ✓/✗ choices are preserved across reruns, keyed by the pair of
biome ids involved (not by row/column position) — so re-running this
after new biomes get added to dimension_biome_planner.xlsx never
touches pairs you already marked, even if the biome list order shifts.

Requires: pip install openpyxl
Usage:
    python pythonScripts/worldgen/generate_biome_adjacency_planner.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import openpyxl
from openpyxl.styles import Font, Alignment, PatternFill
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.datavalidation import DataValidation

from worldgen.constants import (
    CHECK_MARK, CROSS_MARK,
    BIOME_PLANNER_FILE, BIOME_PLANNER_SHEET, BIOME_PLANNER_FIXED_COLUMN_COUNT,
    BIOME_ADJACENCY_PLANNER_FILE,
)

sys.stdout.reconfigure(encoding='utf-8')

MIRROR_FILL = PatternFill('solid', fgColor='D9D9D9')


def load_planner_rows() -> tuple[list[str], list[tuple]]:
    worksheet = openpyxl.load_workbook(BIOME_PLANNER_FILE, data_only=True)[BIOME_PLANNER_SHEET]
    headers   = [cell.value for cell in worksheet[1]]
    dims      = headers[BIOME_PLANNER_FIXED_COLUMN_COUNT:]
    rows      = [
        row for row in worksheet.iter_rows(min_row=2, values_only=True)
        if row[0] and ':' in str(row[0])
    ]
    return dims, rows


def matrix_sheet_name(dim: str) -> str:
    return f"{dim.replace(' Dimension', ' Dim')} Adjacency"[:31]


def load_existing_pair_values(filepath: Path, sheet_name: str) -> dict[frozenset, str]:
    """{frozenset({biome_a, biome_b}): '✓'/'✗'} read back from a previous run."""
    if not filepath.exists():
        return {}
    workbook = openpyxl.load_workbook(filepath, data_only=True)
    if sheet_name not in workbook.sheetnames:
        return {}
    worksheet  = workbook[sheet_name]
    col_biomes = [cell.value for cell in worksheet[1][1:] if cell.value and cell.value != 'Degree']

    pair_values: dict[frozenset, str] = {}
    for row in worksheet.iter_rows(min_row=2, values_only=True):
        row_biome = row[0]
        if not row_biome:
            continue
        for i, col_biome in enumerate(col_biomes):
            value = row[1 + i] if (1 + i) < len(row) else None
            if value in (CHECK_MARK, CROSS_MARK) and row_biome != col_biome:
                pair_values[frozenset({row_biome, col_biome})] = value
    return pair_values


def build_biome_list_sheet(workbook, dim: str, rows: list[tuple], dim_col_idx: int) -> None:
    worksheet = workbook.create_sheet(title=dim)
    worksheet.append(['Biome', 'Source', 'Temperature', 'Humidity'])
    for cell in worksheet[1]:
        cell.font = Font(bold=True)
    for row in rows:
        if row[BIOME_PLANNER_FIXED_COLUMN_COUNT + dim_col_idx] == CHECK_MARK:
            worksheet.append(list(row[:4]))
    for col_idx, width in enumerate([45, 12, 12, 12], start=1):
        worksheet.column_dimensions[get_column_letter(col_idx)].width = width
    worksheet.freeze_panes = 'A2'


def build_matrix_sheet(workbook, dim: str, biomes: list[str], existing: dict[frozenset, str]) -> None:
    worksheet = workbook.create_sheet(title=matrix_sheet_name(dim))
    biome_count = len(biomes)

    worksheet.cell(row=1, column=1, value='Biome')
    for col_offset, biome in enumerate(biomes):
        col  = col_offset + 2
        cell = worksheet.cell(row=1, column=col, value=biome)
        cell.font      = Font(bold=True)
        cell.alignment = Alignment(text_rotation=90, horizontal='center')
        worksheet.column_dimensions[get_column_letter(col)].width = 4
    degree_col = biome_count + 2
    worksheet.cell(row=1, column=degree_col, value='Degree').font = Font(bold=True)
    worksheet.column_dimensions[get_column_letter(degree_col)].width = 8
    worksheet.column_dimensions['A'].width = 45

    if biome_count == 0:
        return

    dropdown = DataValidation(
        type='list', formula1=f'"{CHECK_MARK},{CROSS_MARK}"', allow_blank=True,
        error='Choose a checkmark or a cross from the dropdown.', errorTitle='Invalid entry',
    )
    worksheet.add_data_validation(dropdown)

    for row_offset, row_biome in enumerate(biomes):
        row = row_offset + 2
        worksheet.cell(row=row, column=1, value=row_biome).font = Font(bold=True)
        for col_offset, col_biome in enumerate(biomes):
            col = col_offset + 2
            if col_offset == row_offset:
                worksheet.cell(row=row, column=col, value='-')
            elif col_offset > row_offset:
                value = existing.get(frozenset({row_biome, col_biome}))
                cell  = worksheet.cell(row=row, column=col, value=value)
                dropdown.add(cell.coordinate)
            else:
                mirror_col = get_column_letter(row_offset + 2)
                mirror_row = col_offset + 2
                cell = worksheet.cell(row=row, column=col, value=f'={mirror_col}{mirror_row}')
                cell.fill = MIRROR_FILL
        row_range = f'B{row}:{get_column_letter(biome_count + 1)}{row}'
        worksheet.cell(row=row, column=degree_col, value=f'=COUNTIF({row_range},"{CHECK_MARK}")')

    worksheet.freeze_panes = 'B2'


def main() -> None:
    if not BIOME_PLANNER_FILE.exists():
        print(f'ERROR: {BIOME_PLANNER_FILE} not found.')
        raise SystemExit(1)

    dims, rows = load_planner_rows()
    if not dims:
        print('No dimension columns found in biome planner.')
        raise SystemExit(1)

    existing_per_dim = {
        dim: load_existing_pair_values(BIOME_ADJACENCY_PLANNER_FILE, matrix_sheet_name(dim))
        for dim in dims
    }

    workbook = openpyxl.Workbook()
    workbook.remove(workbook.active)

    for dim_col_idx, dim in enumerate(dims):
        biomes = [
            row[0] for row in rows
            if row[BIOME_PLANNER_FIXED_COLUMN_COUNT + dim_col_idx] == CHECK_MARK
        ]
        build_biome_list_sheet(workbook, dim, rows, dim_col_idx)
        build_matrix_sheet(workbook, dim, biomes, existing_per_dim[dim])
        preserved = len(existing_per_dim[dim])
        print(f'  [{dim}]  {len(biomes)} biomes, {preserved} pair(s) carried over')

    workbook.save(BIOME_ADJACENCY_PLANNER_FILE)
    print(f'\nWrote {BIOME_ADJACENCY_PLANNER_FILE}  ({len(dims)} dimensions)')


main()
