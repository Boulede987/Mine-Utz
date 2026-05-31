"""
extract_textures.py
===================
Extracts entity face crops from the vanilla client jar and all mod jars.

For each entity in lists/mobs.txt, finds the best-matching texture under
assets/<ns>/textures/entity/, applies the standard Minecraft head UV crop
(front face of the head cube), and falls back to the full texture sheet when
that region is transparent (ghast, ender dragon, etc.).

The result is upscaled to 64x64 with nearest-neighbour filtering to preserve
the pixel-art look, then base64-encoded.

Output: lists/entity_textures.txt
Format: entity_id | base64_png

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_textures.py

Requires:
  pip install pillow
"""

import base64
import io
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from PIL import Image, ImageStat

from lib.paths       import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils    import write_txt
from lib.loaders     import load_registry
from lib.jar_scanner import iter_jars

ENTITY_TEX_INFIX = '/textures/entity/'

# ── Load entity IDs ───────────────────────────────────────────────────────────

all_entities = load_registry(LISTS, 'mobs.txt')
print(f'Loaded {len(all_entities)} entity IDs from mobs.txt\n')

# ── Build texture map: (ns, rel_path) -> raw_bytes ────────────────────────────
# rel_path is relative to assets/<ns>/textures/entity/
# e.g. "zombie/zombie.png", "enderdragon/dragon.png"

texture_map: dict[tuple[str, str], bytes] = {}


def scan_jar_for_textures(jar_path: Path) -> int:
    count = 0
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            for entry in zip_file.namelist():
                if ENTITY_TEX_INFIX not in entry or not entry.endswith('.png'):
                    continue
                idx       = entry.index(ENTITY_TEX_INFIX)
                namespace = entry[len('assets/'):idx]
                relative  = entry[idx + len(ENTITY_TEX_INFIX):]
                # First writer wins — vanilla entries are loaded first
                if (namespace, relative) not in texture_map:
                    texture_map[(namespace, relative)] = zip_file.read(entry)
                    count += 1
    except zipfile.BadZipFile:
        print(f'  !! Bad zip: {jar_path.name}')
    except Exception as error:
        print(f'  !! Error in {jar_path.name}: {error}')
    return count


jars = iter_jars(MODS_DIR, extra_jars=[VANILLA_JAR])

print(f'Scanning {len(jars)} jar(s) for entity textures...')
for jar in jars:
    new_count = scan_jar_for_textures(jar)
    if new_count:
        label = 'vanilla' if jar == VANILLA_JAR else jar.name
        print(f'  {new_count:4d}  {label}')

print(f'\nTotal textures in map: {len(texture_map)}')

# ── Pre-index textures by namespace for fast lookup ───────────────────────────

by_namespace: dict[str, dict[str, bytes]] = defaultdict(dict)
for (namespace, relative), data in texture_map.items():
    by_namespace[namespace][relative] = data

# ── Texture lookup ────────────────────────────────────────────────────────────

def find_entity_texture(namespace: str, name: str) -> bytes | None:
    """
    Given an entity registry ID (namespace, name), find the best-matching PNG.

    Priority:
      1. Exact conventional paths  (zombie/zombie.png, zombie.png)
      2. Same paths with underscores removed  (enderdragon/enderdragon.png)
      3. First file inside a matching folder
      4. Any file whose stem matches the name
    """
    namespace_textures = by_namespace.get(namespace)
    if not namespace_textures:
        return None

    name_no_underscores = name.replace('_', '')

    for candidate in [
        f'{name}/{name}.png',
        f'{name}.png',
        f'{name_no_underscores}/{name_no_underscores}.png',
        f'{name_no_underscores}.png',
    ]:
        if candidate in namespace_textures:
            return namespace_textures[candidate]

    for folder in [name, name_no_underscores]:
        prefix = f'{folder}/'
        for path, data in namespace_textures.items():
            if path.startswith(prefix):
                return data

    for path, data in namespace_textures.items():
        stem = path.rsplit('/', 1)[-1].removesuffix('.png')
        if stem == name or stem == name_no_underscores:
            return data

    return None

# ── Crop function ─────────────────────────────────────────────────────────────

def crop_face(raw: bytes) -> bytes:
    """
    Crop the front face of the head cube from a Minecraft entity texture sheet.

    Standard Minecraft UV layout (on a 64-unit-wide sheet):
      Head front face occupies x:[8,16], y:[8,16]  (scaled to actual pixel size)

    If that region is >90% transparent, fall back to the full texture sheet.
    Result is upscaled to 64x64 with nearest-neighbour (preserves pixel art).
    """
    img = Image.open(io.BytesIO(raw)).convert('RGBA')
    width, height = img.size
    scale = width / 64

    x0, y0 = int(8 * scale), int(8 * scale)
    x1, y1 = int(16 * scale), int(16 * scale)
    crop = img.crop((x0, y0, x1, y1))

    stat = ImageStat.Stat(crop)
    if stat.mean[3] < 25:       # mean alpha < ~10% → mostly transparent
        crop = img              # fall back to full texture sheet

    out = crop.resize((64, 64), Image.NEAREST)
    buffer = io.BytesIO()
    out.save(buffer, format='PNG', optimize=True)
    return buffer.getvalue()

# ── Process entities ──────────────────────────────────────────────────────────

rows    = []
matched = 0
missing = 0

for entity_id in sorted(all_entities):
    if ':' not in entity_id:
        continue
    namespace, name = entity_id.split(':', 1)
    raw = find_entity_texture(namespace, name)
    if raw is None:
        missing += 1
        continue
    png_bytes   = crop_face(raw)
    encoded_png = base64.b64encode(png_bytes).decode('ascii')
    rows.append(f'{entity_id} | {encoded_png}')
    matched += 1

print(f'\nMatched   : {matched}')
print(f'No texture: {missing}')

# ── Write output ──────────────────────────────────────────────────────────────

write_txt(LISTS, 'entity_textures.txt', [(
    'ENTITY TEXTURES  format: entity_id | base64_png',
    rows,
)])

print('Done.')
