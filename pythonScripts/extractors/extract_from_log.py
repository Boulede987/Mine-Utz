"""
extract_from_log.py
===================
Parses the KubeJS server log and extracts registry dumps
produced by /dump_all into individual .txt files in lists/.

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_from_log.py
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths import LOG_FILE, LISTS

LABEL_TO_FILE = {
    'STRUCTURE':        'structures.txt',
    'BIOME':            'biomes.txt',
    'DIMENSION':        'dimensions.txt',
    'ITEM':             'items.txt',
    'BLOCK':            'blocks.txt',
    'MOB':              'mobs.txt',
    'ENCHANTMENT':      'enchantments.txt',
    'EFFECT':           'effects.txt',
    'SOUND':            'sounds.txt',
    'ENTITY LOOTTABLE': 'entity_loottables.txt',
    'FOOD STATS':       'food_stats.txt',
    'TOOL STATS':       'tool_stats.txt',
    'ARMOR STATS':      'armor_stats.txt',
    'BLOCK STATS':      'block_stats.txt',
    'ENTITY STATS':     'entity_stats.txt',
    'BIOME STATS':      'biome_stats.txt',
    'ITEM EXTRA':       'item_extra.txt',
}

DUMP_PATTERN = re.compile(
    r'===== ([\w ]+?) DUMP START =====\n(.*?)\n===== [\w ]+ DUMP END \([^)]+\) =====',
    re.DOTALL,
)
PREFIX_PATTERN = re.compile(r'^\[[\d:]+\] \[INFO\] ')


def _read_clean_log() -> str:
    raw_text = LOG_FILE.read_text(encoding='utf-8', errors='ignore')
    return '\n'.join(PREFIX_PATTERN.sub('', line) for line in raw_text.splitlines())


def _write_dump_files(cleaned_log: str) -> None:
    for match in DUMP_PATTERN.finditer(cleaned_log):
        label   = match.group(1).strip()
        content = match.group(2).strip()

        filename = LABEL_TO_FILE.get(label)
        if not filename:
            print(f'Unknown label: {label!r}')
            continue

        destination = LISTS / filename
        destination.write_text(content, encoding='utf-8')
        line_count = content.count('\n') + 1
        print(f'Wrote {destination}  ({line_count} lines)')


def main() -> None:
    cleaned_log = _read_clean_log()
    _write_dump_files(cleaned_log)
    print('Done.')


main()
