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

from lib.paths       import MODS_DIR, LISTS, VANILLA_JAR
from lib.loaders     import load_registry
from lib.jar_scanner import iter_jars

TARGET_SIZE = 32


def _scan_jars_for_textures() -> dict[tuple[str, str, str], bytes]:
    tex_map: dict[tuple[str, str, str], bytes] = {}
    jars = iter_jars(MODS_DIR, extra_jars=[VANILLA_JAR])
    print(f'Scanning {len(jars)} jar(s)...')
    for jar in jars:
        new_count = _scan_jar_for_item_textures(jar, tex_map)
        if new_count:
            label = 'vanilla' if jar == VANILLA_JAR else jar.name
            print(f'  {new_count:5d}  {label}')
    print(f'  {len(tex_map)} total texture entries\n')
    return tex_map


def _scan_jar_for_item_textures(jar_path: Path, tex_map: dict) -> int:
    count = 0
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            for entry in zip_file.namelist():
                if not entry.endswith('.png'):
                    continue
                for texture_type in ('item', 'block'):
                    infix = f'/textures/{texture_type}/'
                    if infix not in entry:
                        continue
                    idx               = entry.index(infix)
                    texture_namespace = entry[len('assets/'):idx]
                    relative          = entry[idx + len(infix):]
                    key               = (texture_namespace, texture_type, relative)
                    if key not in tex_map:
                        tex_map[key] = zip_file.read(entry)
                        count += 1
    except zipfile.BadZipFile:
        print(f'  !! Bad zip: {jar_path.name}')
    except Exception as error:
        print(f'  !! {jar_path.name}: {error}')
    return count


def _build_texture_index(tex_map: dict) -> dict[tuple[str, str], dict[str, bytes]]:
    by_namespace_and_type: dict[tuple[str, str], dict[str, bytes]] = {}
    for (texture_namespace, texture_type, relative), data in tex_map.items():
        key = (texture_namespace, texture_type)
        by_namespace_and_type.setdefault(key, {})[relative] = data
    return by_namespace_and_type


def _crop_and_encode(raw: bytes) -> str | None:
    try:
        img = Image.open(io.BytesIO(raw)).convert('RGBA')
        width, height = img.size
        # Animated sprite sheet: height is a multiple of width — take first frame
        if height > width and width > 0 and height % width == 0:
            img = img.crop((0, 0, width, width))
        img = img.resize((TARGET_SIZE, TARGET_SIZE), Image.NEAREST)
        buffer = io.BytesIO()
        img.save(buffer, format='PNG')
        return base64.b64encode(buffer.getvalue()).decode('ascii')
    except Exception:
        return None


def _find_texture(namespace: str, name: str, texture_index: dict) -> str | None:
    candidates = [
        (namespace, 'item',  f'{name}.png'),
        (namespace, 'block', f'{name}.png'),
        (namespace, 'block', f'{name}_top.png'),
        (namespace, 'block', f'{name}_side.png'),
    ]
    for (texture_namespace, texture_type, relative) in candidates:
        raw = texture_index.get((texture_namespace, texture_type), {}).get(relative)
        if raw:
            return _crop_and_encode(raw)
    return None


def _match_items_to_textures(all_items: set, texture_index: dict) -> list[str]:
    print('Matching items to textures...')
    lines   = ['===== ITEM TEXTURES  format: item_id | base64_png =====']
    found   = 0
    missing = 0
    for item_id in sorted(all_items):
        if ':' not in item_id:
            continue
        namespace, name = item_id.split(':', 1)
        encoded_png = _find_texture(namespace, name, texture_index)
        if encoded_png:
            lines.append(f'{item_id} | {encoded_png}')
            found += 1
        else:
            missing += 1
    print(f'Done: {found} found, {missing} missing')
    return lines


def main() -> None:
    all_items     = load_registry(LISTS, 'items.txt')
    print(f'Loaded {len(all_items)} item IDs from items.txt')

    tex_map       = _scan_jars_for_textures()
    texture_index = _build_texture_index(tex_map)
    lines         = _match_items_to_textures(all_items, texture_index)

    out = LISTS / 'item_textures.txt'
    out.write_text('\n'.join(lines), encoding='utf-8')


main()
