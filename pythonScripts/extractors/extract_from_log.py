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

# ── Label → output filename ───────────────────────────────────────────────────

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
}

# ── Parse ─────────────────────────────────────────────────────────────────────

raw_text = LOG_FILE.read_text(encoding='utf-8', errors='ignore')

# Strip timestamp/INFO prefix from every line
prefix_pattern = re.compile(r'^\[[\d:]+\] \[INFO\] ')
cleaned_text   = '\n'.join(
    prefix_pattern.sub('', line) for line in raw_text.splitlines()
)

# Match dump blocks — label may contain spaces (e.g. ENTITY LOOTTABLE)
# End marker may have extra text like ", 472 with no loot table"
dump_pattern = re.compile(
    r'===== ([\w ]+?) DUMP START =====\n(.*?)\n===== [\w ]+ DUMP END \([^)]+\) =====',
    re.DOTALL,
)

# ── Write ─────────────────────────────────────────────────────────────────────

for match in dump_pattern.finditer(cleaned_text):
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

print('Done.')
