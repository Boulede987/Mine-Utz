"""
extract_biome_blocks.py
=======================
Resolves which blocks generate in which biomes by traversing the
worldgen feature chain:

  biome_features.txt
    → worldgen/placed_feature/<id>.json   (placed_feature → configured_feature)
    → worldgen/configured_feature/<id>.json  (configured_feature → block names)

Feature types covered:
  minecraft:ore / minecraft:scattered_ore  — ores and mineral veins
  minecraft:disk                           — clay, gravel, sand deposits

Other feature types (trees, flowers, vegetation) are intentionally skipped —
they don't produce meaningful block-in-biome data for the use cases of this DB.

Sources
-------
  lists/biome_features.txt         (from extract_from_jars.py)
  vanilla client jar + mod jars    (for placed/configured_feature JSONs)

Output: lists/biome_blocks.txt
Format: biome_id | block_id

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/extract_biome_blocks.py
"""

import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths    import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils import write_txt
from lib.loaders  import load_pipe_file

BLOCK_FEATURE_TYPES = {
    'minecraft:ore',
    'minecraft:scattered_ore',
    'minecraft:disk',
}

# ── Load biome → feature IDs from lists/biome_features.txt ───────────────────

biome_features: dict[str, set[str]] = defaultdict(set)

for row in load_pipe_file(LISTS, 'biome_features.txt'):
    parts = [p.strip() for p in row.split('|', 1)]
    if len(parts) == 2:
        biome_features[parts[0]].add(parts[1])

print(f'Biomes loaded          : {len(biome_features)}')
print(f'Total biome->feature refs: {sum(len(v) for v in biome_features.values())}')

# ── Scan jars for placed_feature and configured_feature JSONs ─────────────────

# placed_feature_id → configured_feature_id
placed_to_cfg: dict[str, str] = {}

# configured_feature_id → set of block_ids
cfg_to_blocks: dict[str, set[str]] = {}


def _scan_jar(jar_path: Path) -> None:
    try:
        with zipfile.ZipFile(jar_path, 'r') as zf:
            for entry in zf.namelist():
                if not entry.startswith('data/') or not entry.endswith('.json'):
                    continue
                parts = entry.split('/', 2)
                if len(parts) < 3:
                    continue
                ns, relative = parts[1], parts[2]

                if relative.startswith('worldgen/placed_feature/'):
                    feat_name = relative[len('worldgen/placed_feature/'):].removesuffix('.json')
                    feat_id   = f'{ns}:{feat_name}'
                    if feat_id not in placed_to_cfg:
                        try:
                            data = json.loads(zf.read(entry))
                            cfg  = data.get('feature', '')
                            # feature may be an inline object for some mods — skip those
                            if isinstance(cfg, str) and cfg:
                                placed_to_cfg[feat_id] = cfg
                        except Exception:
                            pass

                elif relative.startswith('worldgen/configured_feature/'):
                    cfg_name = relative[len('worldgen/configured_feature/'):].removesuffix('.json')
                    cfg_id   = f'{ns}:{cfg_name}'
                    if cfg_id not in cfg_to_blocks:
                        try:
                            data  = json.loads(zf.read(entry))
                            ftype = data.get('type', '')
                            if ftype not in BLOCK_FEATURE_TYPES:
                                continue
                            config = data.get('config', {})
                            blocks: set[str] = set()

                            if ftype in ('minecraft:ore', 'minecraft:scattered_ore'):
                                for target in config.get('targets', []):
                                    name = target.get('state', {}).get('Name', '')
                                    if name:
                                        blocks.add(name)

                            elif ftype == 'minecraft:disk':
                                name = config.get('state', {}).get('Name', '')
                                if name:
                                    blocks.add(name)
                                for s in config.get('states', []):
                                    name = s.get('Name', '')
                                    if name:
                                        blocks.add(name)

                            if blocks:
                                cfg_to_blocks[cfg_id] = blocks
                        except Exception:
                            pass

    except zipfile.BadZipFile:
        pass
    except Exception as e:
        print(f'  !! {jar_path.name}: {e}')


jars = []
if VANILLA_JAR.exists():
    jars.append(VANILLA_JAR)
else:
    print(f'WARNING: vanilla jar not found at {VANILLA_JAR}')
jars.extend(sorted(MODS_DIR.glob('*.jar')))

print(f'\nScanning {len(jars)} jar(s) for worldgen feature JSONs...')
for jar in jars:
    _scan_jar(jar)

print(f'placed_feature entries       : {len(placed_to_cfg)}')
print(f'configured_feature w/ blocks : {len(cfg_to_blocks)}')

# ── Resolve biome → block ─────────────────────────────────────────────────────

rows: set[str] = set()

for biome_id, feature_ids in biome_features.items():
    for feat_id in feature_ids:
        cfg_id = placed_to_cfg.get(feat_id)
        if not cfg_id:
            continue
        blocks = cfg_to_blocks.get(cfg_id)
        if not blocks:
            continue
        for block_id in blocks:
            rows.add(f'{biome_id} | {block_id}')

print(f'Biome->block pairs           : {len(rows)}')

# ── Write output ──────────────────────────────────────────────────────────────

write_txt(LISTS, 'biome_blocks.txt', [(
    'BIOME BLOCKS  format: biome_id | block_id',
    sorted(rows),
)])

print('Done.')
