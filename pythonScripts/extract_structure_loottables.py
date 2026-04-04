"""
extract_structure_loottables.py
================================
Scans mod jars for structure template .nbt files and extracts
loot table assignments from chest block entities.

Output: lists/structure_loottables.txt
Format: structure_id | block_type | position | loottable_id

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_structure_loottables.py

Requires:
  pip install nbtlib
"""

import gzip
import io
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import nbtlib

from lib.paths    import MODS_DIR, LISTS
from lib.io_utils import write_txt
from lib.jar_scanner import iter_jars

# ── Scan ──────────────────────────────────────────────────────────────────────

result_rows = []

jars = iter_jars(MODS_DIR)
print(f'Scanning {len(jars)} jars for structure NBT files...\n')

for jar_path in jars:
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            for entry_name in zip_file.namelist():
                if not entry_name.startswith('data/') or not entry_name.endswith('.nbt'):
                    continue

                parts = entry_name.split('/', 2)
                if len(parts) < 3:
                    continue

                namespace, relative = parts[1], parts[2]

                if not relative.startswith('structures/'):
                    continue

                # Derive structure registry ID from file path
                # e.g. data/aether/structures/dungeon/silver_dungeon.nbt
                #   -> aether:dungeon/silver_dungeon
                structure_name = relative[len('structures/'):].removesuffix('.nbt')
                structure_id   = f"{namespace}:{structure_name}"

                try:
                    raw_bytes = zip_file.read(entry_name)

                    # Minecraft NBT files are gzip-compressed.
                    # Decompress first, then parse as raw NBT.
                    try:
                        raw_bytes = gzip.decompress(raw_bytes)
                    except Exception:
                        pass  # Not gzip compressed, try parsing as-is

                    nbt_data = nbtlib.File.parse(io.BytesIO(raw_bytes))
                    root     = nbt_data.get('') or nbt_data

                    palette     = root.get('palette', [])
                    blocks      = root.get('blocks', [])

                    # Build palette index → block name lookup
                    palette_map = {
                        index: str(entry.get('Name', ''))
                        for index, entry in enumerate(palette)
                    }

                    for block in blocks:
                        block_nbt = block.get('nbt')
                        if not block_nbt:
                            continue

                        loot_table = block_nbt.get('LootTable')
                        if not loot_table:
                            continue

                        loot_str   = str(loot_table)
                        position   = block.get('pos', [0, 0, 0])
                        pos_str    = f"{position[0]},{position[1]},{position[2]}"
                        state_idx  = int(block.get('state', 0))
                        block_name = palette_map.get(state_idx, 'unknown')

                        result_rows.append(
                            f"{structure_id} | {block_name} | {pos_str} | {loot_str}"
                        )

                except Exception as parse_error:
                    print(f'  !! Could not parse {entry_name} in {jar_path.name}: {parse_error}')

    except zipfile.BadZipFile:
        print(f'  !! Bad zip: {jar_path.name}')
    except Exception as error:
        print(f'  !! Error in {jar_path.name}: {error}')

# ── Write output ──────────────────────────────────────────────────────────────

write_txt(LISTS, 'structure_loottables.txt', [(
    'STRUCTURE CHEST LOOT TABLES  format: structure_id | block_type | position | loottable',
    result_rows,
)])

print('Done.')