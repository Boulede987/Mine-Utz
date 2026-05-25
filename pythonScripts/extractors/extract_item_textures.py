"""
extract_item_textures.py
========================
Extracts item and block sprites from the vanilla client jar and all mod jars.

For each item in lists/items.txt, looks for:
  1. assets/<ns>/textures/item/<name>.png
  2. assets/<ns>/textures/block/<name>.png        (items that are blocks)
  3. assets/<ns>/textures/block/<name>_top.png    (directional blocks)

Animated textures (height > width) are cropped to first frame.
All sprites upscaled to 32x32 nearest-neighbour.

Outputs:
  lists/item_textures.txt   format: item_id | base64_png

Requires: pip install pillow
"""

import base64
import io
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from PIL import Image

from lib.paths   import MODS_DIR, LISTS, VANILLA_JAR
from lib.loaders import load_registry

TARGET_SIZE = 32

# ── Load item IDs ─────────────────────────────────────────────────────────────

all_items = load_registry(LISTS, 'items.txt')
print(f'Loaded {len(all_items)} item IDs from items.txt')

# ── Scan jars for item + block textures ───────────────────────────────────────
# Maps: (ns, 'item', rel_name) -> bytes  and  (ns, 'block', rel_name) -> bytes

tex_map: dict[tuple[str, str, str], bytes] = {}


def scan_jar(jar_path: Path) -> int:
    count = 0
    try:
        with zipfile.ZipFile(jar_path, 'r') as zf:
            for entry in zf.namelist():
                if not entry.endswith('.png'):
                    continue
                for tex_type in ('item', 'block'):
                    infix = f'/textures/{tex_type}/'
                    if infix not in entry:
                        continue
                    idx = entry.index(infix)
                    ns  = entry[len('assets/'):idx]
                    rel = entry[idx + len(infix):]        # e.g. "diamond_sword.png"
                    key = (ns, tex_type, rel)
                    if key not in tex_map:
                        tex_map[key] = zf.read(entry)
                        count += 1
    except zipfile.BadZipFile:
        print(f'  !! Bad zip: {jar_path.name}')
    except Exception as e:
        print(f'  !! {jar_path.name}: {e}')
    return count


jars = []
if VANILLA_JAR.exists():
    jars.append(VANILLA_JAR)
else:
    print(f'WARNING: vanilla jar not found at {VANILLA_JAR}')
jars.extend(sorted(MODS_DIR.glob('*.jar')))

print(f'Scanning {len(jars)} jar(s)...')
for jar in jars:
    n = scan_jar(jar)
    if n:
        label = 'vanilla' if jar == VANILLA_JAR else jar.name
        print(f'  {n:5d}  {label}')

print(f'  {len(tex_map)} total texture entries\n')

# ── Index by namespace for fast lookup ────────────────────────────────────────

by_ns_type: dict[tuple[str, str], dict[str, bytes]] = {}
for (ns, tex_type, rel), data in tex_map.items():
    key = (ns, tex_type)
    by_ns_type.setdefault(key, {})[rel] = data

# ── Sprite extraction ─────────────────────────────────────────────────────────

def crop_and_encode(raw: bytes) -> str | None:
    try:
        img = Image.open(io.BytesIO(raw)).convert('RGBA')
        w, h = img.size
        # Animated sprite sheet: height is a multiple of width — take first frame
        if h > w and w > 0 and h % w == 0:
            img = img.crop((0, 0, w, w))
        img = img.resize((TARGET_SIZE, TARGET_SIZE), Image.NEAREST)
        buf = io.BytesIO()
        img.save(buf, format='PNG')
        return base64.b64encode(buf.getvalue()).decode('ascii')
    except Exception:
        return None


def find_texture(ns: str, name: str) -> str | None:
    """Try item/ then block/ locations for a given (ns, name)."""
    name_no_ns = name  # already stripped of namespace

    candidates = [
        (ns, 'item',  f'{name_no_ns}.png'),
        (ns, 'block', f'{name_no_ns}.png'),
        (ns, 'block', f'{name_no_ns}_top.png'),
        (ns, 'block', f'{name_no_ns}_side.png'),
    ]
    for (tns, ttype, rel) in candidates:
        raw = by_ns_type.get((tns, ttype), {}).get(rel)
        if raw:
            return crop_and_encode(raw)
    return None

# ── Match items to textures ───────────────────────────────────────────────────

print('Matching items to textures...')
lines   = ['===== ITEM TEXTURES  format: item_id | base64_png =====']
found   = 0
missing = 0

for item_id in sorted(all_items):
    if ':' not in item_id:
        continue
    ns, name = item_id.split(':', 1)
    b64 = find_texture(ns, name)
    if b64:
        lines.append(f'{item_id} | {b64}')
        found += 1
    else:
        missing += 1

out = LISTS / 'item_textures.txt'
out.write_text('\n'.join(lines), encoding='utf-8')
print(f'Done: {found} found, {missing} missing → lists/item_textures.txt')
