"""
lib/loaders.py
==============
Loaders for the extracted .txt files in lists/.
Handles both formats produced by our extractors:

  Format A (extract_from_log.py) - no block markers:
    --- namespace ---
    namespace:entry

  Format B (extract_from_jars.py) - block markers:
    ===== LABEL =====
    namespace:entry
    ===== END (N total) =====
"""

from pathlib import Path


def _has_block_markers(lines: list[str]) -> bool:
    return any(
        '=====' in line and ('START' in line or 'END' in line or 'format' in line.lower())
        for line in lines
    )


def load_registry(lists_dir: Path, filename: str) -> set[str]:
    """
    Load a registry dump file and return a set of registry name strings.
    Only includes lines that look like registry names (contain ':').
    """
    path = lists_dir / filename
    if not path.exists():
        print(f'  WARNING: {filename} not found')
        return set()

    lines    = path.read_text(encoding='utf-8', errors='ignore').splitlines()
    entries  = set()
    in_block = False
    use_markers = _has_block_markers(lines)

    for raw_line in lines:
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith('====='):
            if use_markers:
                in_block = 'END' not in line and 'total' not in line
            continue
        if use_markers and not in_block:
            continue
        if line.startswith('---'):
            continue
        # Only keep valid registry names (namespace:name, no spaces)
        if ':' in line and ' ' not in line:
            entries.add(line)

    return entries


def load_pipe_file(lists_dir: Path, filename: str, column: int | None = None) -> set | list:
    """
    Load a pipe-separated file.
    Returns:
      - set of values from the given column index if column is specified
      - list of full row strings if column is None

    Handles both marker and marker-less formats.
    """
    path = lists_dir / filename
    if not path.exists():
        return set() if column is not None else []

    lines    = path.read_text(encoding='utf-8', errors='ignore').splitlines()
    results  = set() if column is not None else []
    in_block = False
    use_markers = _has_block_markers(lines)

    for raw_line in lines:
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith('====='):
            if use_markers:
                in_block = 'END' not in line and 'total' not in line
            continue
        if use_markers and not in_block:
            continue
        if line.startswith('---'):
            continue
        if '|' not in line:
            continue

        parts = [part.strip() for part in line.split('|')]

        if column is not None:
            if column < len(parts):
                results.add(parts[column])
        else:
            results.append(line)

    return results
