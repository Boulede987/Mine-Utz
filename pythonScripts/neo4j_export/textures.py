import base64

from .config import LISTS, WEBUI_TEXTURES


def load_texture_file(filename: str, label: str) -> dict[str, str]:
    path = LISTS / filename
    if not path.exists():
        print(f"  {filename} not found -- skipping")
        return {}
    mapping = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('=') or line.startswith('#'):
            continue
        parts = [part.strip() for part in line.split('|', 1)]
        if len(parts) != 2:
            continue
        item_id, base64_data = parts
        output_path = WEBUI_TEXTURES / (item_id.replace(':', '__') + '.png')
        try:
            output_path.write_bytes(base64.b64decode(base64_data))
            mapping[item_id] = output_path.name
        except Exception:
            pass
    print(f"  {len(mapping)} {label} textures saved")
    return mapping


def export_textures() -> dict[str, str]:
    WEBUI_TEXTURES.mkdir(parents=True, exist_ok=True)
    mapping = {}
    mapping.update(load_texture_file('entity_textures.txt', 'entity'))
    mapping.update(load_texture_file('item_textures.txt',   'item'))
    return mapping
