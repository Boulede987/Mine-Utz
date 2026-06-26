"""
worldgen/loaders.py
===================
Shared xlsx loaders for worldgen scripts.
"""

import openpyxl

from worldgen.constants import (
    CHECK_MARK, BIOME_PLANNER_FIXED_COLUMN_COUNT,
    BIOME_PLANNER_FILE, BIOME_PLANNER_SHEET,
)


def load_dimension_biomes() -> dict[str, list[str]]:
    """
    Returns {dim_header: [biome_id, ...]} for each dimension column in the biome planner.
    Biomes are those marked ✓. Order matches the planner column order.
    """
    if not BIOME_PLANNER_FILE.exists():
        print(f'ERROR: {BIOME_PLANNER_FILE} not found.')
        raise SystemExit(1)

    worksheet = openpyxl.load_workbook(BIOME_PLANNER_FILE)[BIOME_PLANNER_SHEET]
    headers   = [cell.value for cell in worksheet[1]]
    dim_cols  = [
        (col_idx, header)
        for col_idx, header in enumerate(headers)
        if col_idx >= BIOME_PLANNER_FIXED_COLUMN_COUNT and header
    ]
    result: dict[str, list[str]] = {}
    for col_idx, header in dim_cols:
        result[header] = [
            str(row[0]) for row in worksheet.iter_rows(min_row=2, values_only=True)
            if row[0] and ':' in str(row[0]) and row[col_idx] == CHECK_MARK
        ]
    return result
