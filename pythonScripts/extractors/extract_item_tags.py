"""
extract_item_tags.py
=====================
Scans vanilla client jar and all mod jars for item tag files and writes
the merged tag->item mapping to lists/item_tags.txt.

Source jars
-----------
  1. Vanilla 1.21.1 client jar
  2. All .jar files in minecraft/mods/

Tag files matched: data/<ns>/tags/items/<path>.json
Tag ID derived as: <ns>:<path>   (e.g. minecraft:swords, minecraft:helmets)

Output: lists/item_tags.txt
Format: tag_id | member_or_subtag

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_item_tags.py
"""

import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from lib.paths    import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils import write_txt

TAG_PREFIX   = 'data/'
TAG_SUFFIX   = '.json'
# 1.21.1 vanilla + updated mods use singular 'item'; older mods use plural 'items'
TAG_INFIXES  = ['/tags/item/', '/tags/items/']


def iter_jars() -> list[Path]:
    jars = []
    if VANILLA_JAR.exists():
        jars.append(VANILLA_JAR)
    else:
        print(f'WARNING: vanilla jar not found at {VANILLA_JAR}')
    jars.extend(sorted(MODS_DIR.glob('*.jar')))
    return jars


def _strip_tag_infix(relative: str) -> str | None:
    """Return the tag path after stripping whichever tags/item[s]/ prefix matches."""
    for infix in ('tags/item/', 'tags/items/'):
        if relative.startswith(infix):
            return relative[len(infix):].removesuffix('.json')
    return None


def scan_tag_file(zip_file: zipfile.ZipFile, entry_name: str, tag_map: dict[str, list[str]]) -> None:
    parts = entry_name.split('/', 2)
    if len(parts) < 3:
        return
    namespace = parts[1]
    relative  = parts[2]   # tags/item[s]/<path>.json
    tag_path  = _strip_tag_infix(relative)
    if tag_path is None:
        return
    tag_id    = f'{namespace}:{tag_path}'

    try:
        data = json.loads(zip_file.read(entry_name))
    except Exception:
        return

    replace    = data.get('replace', False)
    raw_values = data.get('values', [])
    values = [
        v if isinstance(v, str) else v['id']
        for v in raw_values
        if isinstance(v, str) or (isinstance(v, dict) and 'id' in v)
    ]

    if replace:
        tag_map[tag_id] = values
    else:
        tag_map.setdefault(tag_id, []).extend(values)


# ── Scan ──────────────────────────────────────────────────────────────────────

tag_map: dict[str, list[str]] = {}
jars = iter_jars()
print(f'Scanning {len(jars)} jar(s) for item tag files...\n')

for jar_path in jars:
    label = 'vanilla' if jar_path == VANILLA_JAR else jar_path.name
    try:
        with zipfile.ZipFile(jar_path, 'r') as zf:
            tag_entries = [
                n for n in zf.namelist()
                if n.startswith(TAG_PREFIX)
                and any(infix in n for infix in TAG_INFIXES)
                and n.endswith(TAG_SUFFIX)
            ]
            if tag_entries:
                print(f'  {label[:60]:60s}  {len(tag_entries)} tag file(s)')
            for entry in tag_entries:
                scan_tag_file(zf, entry, tag_map)

    except zipfile.BadZipFile:
        print(f'  !! Bad zip: {label}')
    except Exception as e:
        print(f'  !! Error in {label}: {e}')

# ── Deduplicate ───────────────────────────────────────────────────────────────

deduped: dict[str, list[str]] = {
    tag: list(dict.fromkeys(values))
    for tag, values in sorted(tag_map.items())
}

total_values = sum(len(v) for v in deduped.values())
print(f'\nUnique tags collected : {len(deduped)}')
print(f'Total tag values      : {total_values}')

# ── Write ─────────────────────────────────────────────────────────────────────

rows = [
    f'{tag} | {value}'
    for tag, values in deduped.items()
    for value in values
]

write_txt(LISTS, 'item_tags.txt', [(
    'ITEM TAGS  format: tag_id | member_or_subtag',
    rows,
)])

print('Done.')
