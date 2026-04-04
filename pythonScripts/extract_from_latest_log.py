import re
from pathlib import Path

base = Path(__file__).parent.parent / 'minecraft'
log  = base / 'logs' / 'kubejs' / 'server.log'
out  = Path(__file__).parent.parent / 'lists' 

out.mkdir(parents=True, exist_ok=True)

text = log.read_text(encoding='utf-8', errors='ignore')

# Strip timestamp/INFO prefix from every line
prefix  = re.compile(r'^\[[\d:]+\] \[INFO\] ')
cleaned = '\n'.join(prefix.sub('', line) for line in text.splitlines())

label_to_file = {
    'STRUCTURE':        'structures.txt',
    'BIOME':            'biomes.txt',
    'DIMENSION':        'dimensions.txt',
    'ITEM':             'items.txt',
    'BLOCK':            'blocks.txt',
    'MOB':              'mobs.txt',
    'ENCHANTMENT':      'enchantments.txt',
    'EFFECT':           'effects.txt',
    'SOUND':            'sounds.txt',
    'ENTITY LOOTTABLE': 'entity_loottables.txt',  # ← add this
}

pattern = re.compile(
    r'===== ([\w ]+?) DUMP START =====\n(.*?)\n===== [\w ]+ DUMP END \([^)]+\) =====',
    re.DOTALL
)

for match in pattern.finditer(cleaned):
    label   = match.group(1)
    content = match.group(2).strip()
    filename = label_to_file.get(label)
    if not filename:
        print('Unknown label: ' + label)
        continue
    dest = out / filename
    dest.write_text(content, encoding='utf-8')
    print('Wrote ' + str(dest) + ' (' + str(content.count('\n') + 1) + ' lines)')

print('Done.')