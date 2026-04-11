"""
lib/tag_resolver.py
====================
Loads a biome_tags.txt file and resolves tag references recursively.

Tag format in biome_tags.txt:
  tag_id | value_or_subtag
  minecraft:has_structure/ancient_city | minecraft:deep_dark
  minecraft:is_overworld | #minecraft:is_beach
  ...
"""

from pathlib import Path


def load_tag_map(lists_dir: Path, filename: str = 'biome_tags.txt') -> dict[str, list[str]]:
    """
    Load biome_tags.txt into a dict: tag_id -> [values].
    Values may be concrete biome IDs or '#tag_id' sub-tag references.
    """
    path = lists_dir / filename
    if not path.exists():
        print(f'  WARNING: {filename} not found — run extract_biome_tags.py first')
        return {}

    tag_map: dict[str, list[str]] = {}
    for raw_line in path.read_text(encoding='utf-8', errors='ignore').splitlines():
        line = raw_line.strip()
        if not line or line.startswith('=====') or line.startswith('---') or '|' not in line:
            continue
        parts = [p.strip() for p in line.split('|', 1)]
        if len(parts) < 2:
            continue
        tag_id, value = parts
        tag_map.setdefault(tag_id, []).append(value)

    return tag_map


def resolve_tag(tag_id: str, tag_map: dict[str, list[str]], _visited: frozenset = frozenset()) -> set[str]:
    """
    Recursively expand a tag ID to a set of concrete biome IDs.
    Circular references are silently ignored.
    """
    if tag_id in _visited:
        return set()

    _visited = _visited | {tag_id}
    result: set[str] = set()

    for value in tag_map.get(tag_id, []):
        if value.startswith('#'):
            result |= resolve_tag(value[1:], tag_map, _visited)
        else:
            result.add(value)

    return result
