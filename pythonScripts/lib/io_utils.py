"""
lib/io_utils.py
===============
File reading and writing utilities shared across all scripts.
"""

import json
import zipfile
from pathlib import Path


def strip_comments(text: str) -> str:
    # Strip // comments outside of string values
    result = []
    in_string = False
    pos = 0
    while pos < len(text):
        char = text[pos]
        if char == '"' and (pos == 0 or text[pos - 1] != '\\'):
            in_string = not in_string
            result.append(char)
        elif not in_string and char == '/' and pos + 1 < len(text) and text[pos + 1] == '/':
            while pos < len(text) and text[pos] != '\n':
                pos += 1
            continue
        else:
            result.append(char)
        pos += 1
    return ''.join(result)


def read_json(zip_file: zipfile.ZipFile, entry_name: str) -> dict | None:
    """Parse a JSON entry from an open ZipFile. Returns None on any error."""
    try:
        raw = zip_file.read(entry_name).decode('utf-8', errors='ignore')
        return json.loads(raw)
    except json.JSONDecodeError:
        try:
            return json.loads(strip_comments(raw))
        except Exception:
            return None
    except Exception:
        return None


def write_section(file_handle, header: str, rows: list[str]) -> None:
    """
    Write a titled block in the standard dump format:
      ===== HEADER =====
      row1
      row2
      ===== END (N total) =====
    """
    file_handle.write(f'===== {header} =====\n')
    for row in rows:
        file_handle.write(row + '\n')
    file_handle.write(f'===== END ({len(rows)} total) =====\n\n')


def write_txt(output_dir: Path, filename: str, sections: list[tuple[str, list[str]]]) -> int:
    """
    Write one or more sections to a .txt file.
    sections = [(header, [row, ...]), ...]
    Returns total number of entries written.
    """
    path = output_dir / filename
    with open(path, 'w', encoding='utf-8') as file_handle:
        for header, rows in sections:
            write_section(file_handle, header, sorted(set(rows)))
    total = sum(len(rows) for _, rows in sections)
    print(f'  Wrote {path}  ({total} entries)')
    return total
