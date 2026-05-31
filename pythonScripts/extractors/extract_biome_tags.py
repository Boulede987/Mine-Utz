"""
extract_biome_tags.py
======================
Scans the vanilla client jar and all mod jars for worldgen biome tag files
and writes the merged tag->biome mapping to lists/biome_tags.txt.

Why both the client jar AND mods?
  The vanilla jar defines the base tags (e.g. which vanilla biomes spawn ancient_city).
  Mods append to those same tags (replace:false) to also spawn their biomes there.
  We merge all sources so tag resolution is complete.

Source jars
-----------
  1. Vanilla 1.21.1 client jar (from Prism's library cache)
  2. All .jar files in minecraft/mods/

Tag files matched: data/<ns>/tags/worldgen/biome/<path>.json
Tag ID derived as: <ns>:<path>   (e.g. minecraft:has_structure/ancient_city)

Output: lists/biome_tags.txt
Format: tag_id | biome_or_subtag   (one row per value, tags may contain '#subtag' refs)

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_biome_tags.py
"""

import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths       import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils    import write_txt
from lib.jar_scanner import iter_jars

TAG_PREFIX   = 'data/'
TAG_INFIX    = '/tags/worldgen/biome/'
TAG_SUFFIX   = '.json'


def scan_tag_file(zip_file: zipfile.ZipFile, entry_name: str, tag_map: dict[str, list[str]]) -> None:
    """Parse one tag JSON file and merge its values into tag_map."""
    # entry_name: data/<ns>/tags/worldgen/biome/<path>.json
    parts = entry_name.split('/', 2)
    if len(parts) < 3:
        return
    namespace  = parts[1]
    relative   = parts[2]          # tags/worldgen/biome/<path>.json
    tag_path   = relative[len('tags/worldgen/biome/'):].removesuffix('.json')
    tag_id     = f'{namespace}:{tag_path}'

    try:
        data = json.loads(zip_file.read(entry_name))
    except Exception:
        return

    replace = data.get('replace', False)

    # Values may be plain strings OR objects like {"id": "minecraft:plains", "required": false}
    raw_values = data.get('values', [])
    values = []
    for v in raw_values:
        if isinstance(v, str):
            values.append(v)
        elif isinstance(v, dict) and 'id' in v:
            values.append(v['id'])

    if replace:
        tag_map[tag_id] = values
    else:
        tag_map.setdefault(tag_id, []).extend(values)


# ── Scan ──────────────────────────────────────────────────────────────────────

tag_map: dict[str, list[str]] = {}   # tag_id -> [values]
jars = iter_jars(MODS_DIR, extra_jars=[VANILLA_JAR])
print(f'Scanning {len(jars)} jar(s) for biome tag files...\n')

for jar_path in jars:
    label = 'vanilla' if jar_path == VANILLA_JAR else jar_path.name
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            tag_entries = [
                entry for entry in zip_file.namelist()
                if entry.startswith(TAG_PREFIX)
                and TAG_INFIX in entry
                and entry.endswith(TAG_SUFFIX)
            ]
            if tag_entries:
                print(f'  {label[:60]:60s}  {len(tag_entries)} tag file(s)')
            for entry in tag_entries:
                scan_tag_file(zip_file, entry, tag_map)

    except zipfile.BadZipFile:
        print(f'  !! Bad zip: {label}')
    except Exception as error:
        print(f'  !! Error in {label}: {error}')

# ── Deduplicate values per tag ────────────────────────────────────────────────

deduped: dict[str, list[str]] = {
    tag: list(dict.fromkeys(values))          # preserve order, remove dupes
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

write_txt(LISTS, 'biome_tags.txt', [(
    'BIOME TAGS  format: tag_id | biome_or_subtag',
    rows,
)])

print('Done.')
