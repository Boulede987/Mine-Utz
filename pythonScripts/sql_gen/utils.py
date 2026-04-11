"""
sql_gen/utils.py
================
Shared helpers for generating SQL INSERT statements.
"""

from pathlib import Path


def val(value) -> str:
    """Format a Python value as a SQL literal."""
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return '1' if value else '0'
    if isinstance(value, (int, float)):
        return str(value)
    escaped = str(value).replace("'", "''").replace('\\', '\\\\')
    return f"'{escaped}'"


def insert_rows(table: str, columns: list[str], rows: list[tuple]) -> str:
    """
    Generate a multi-row INSERT statement.
    Returns a comment line if rows is empty.
    """
    if not rows:
        return f'-- (no rows for {table})\n'

    col_list = ', '.join(columns)
    value_lines = [
        '  (' + ', '.join(val(v) for v in row) + ')'
        for row in rows
    ]
    return (
        f'INSERT INTO {table} ({col_list}) VALUES\n'
        + ',\n'.join(value_lines)
        + ';\n'
    )


def write_sql(output_path: Path, title: str, blocks: list[tuple[str, str]]) -> None:
    """
    Write a complete SQL file.

    blocks is a list of (comment, sql_statement) pairs.
    """
    header = (
        f'-- {"=" * 50}\n'
        f'-- {title}\n'
        f'-- {"=" * 50}\n\n'
        'USE MineutzDB;\n\n'
    )
    body = '\n'.join(
        f'-- {comment}\n{sql}'
        for comment, sql in blocks
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(header + body, encoding='utf-8')
    print(f'Written -> {output_path}')
