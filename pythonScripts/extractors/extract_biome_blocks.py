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

from lib.paths       import MODS_DIR, LISTS, VANILLA_JAR
from lib.io_utils    import write_txt
from lib.loaders     import load_pipe_file
from lib.jar_scanner import iter_jars

BLOCK_FEATURE_TYPES = {
    'minecraft:ore',
    'minecraft:scattered_ore',
    'minecraft:disk',
}


def _load_biome_features() -> dict[str, set[str]]:
    biome_features: dict[str, set[str]] = defaultdict(set)
    for row in load_pipe_file(LISTS, 'biome_features.txt'):
        parts = [p.strip() for p in row.split('|', 1)]
        if len(parts) == 2:
            biome_features[parts[0]].add(parts[1])
    print(f'Biomes loaded            : {len(biome_features)}')
    print(f'Total biome->feature refs: {sum(len(v) for v in biome_features.values())}')
    return biome_features


def _scan_jar_for_features(
    jar_path: Path,
    placed_to_configured_feature: dict[str, str],
    configured_feature_to_blocks: dict[str, set[str]],
) -> None:
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            for entry in zip_file.namelist():
                if not entry.startswith('data/') or not entry.endswith('.json'):
                    continue
                parts = entry.split('/', 2)
                if len(parts) < 3:
                    continue
                namespace, relative = parts[1], parts[2]

                if relative.startswith('worldgen/placed_feature/'):
                    feature_name      = relative[len('worldgen/placed_feature/'):].removesuffix('.json')
                    placed_feature_id = f'{namespace}:{feature_name}'
                    if placed_feature_id not in placed_to_configured_feature:
                        try:
                            data                  = json.loads(zip_file.read(entry))
                            configured_feature_id = data.get('feature', '')
                            # feature may be an inline object for some mods — skip those
                            if isinstance(configured_feature_id, str) and configured_feature_id:
                                placed_to_configured_feature[placed_feature_id] = configured_feature_id
                        except Exception:
                            pass

                elif relative.startswith('worldgen/configured_feature/'):
                    configured_feature_name = relative[len('worldgen/configured_feature/'):].removesuffix('.json')
                    configured_feature_id   = f'{namespace}:{configured_feature_name}'
                    if configured_feature_id not in configured_feature_to_blocks:
                        try:
                            data         = json.loads(zip_file.read(entry))
                            feature_type = data.get('type', '')
                            if feature_type not in BLOCK_FEATURE_TYPES:
                                continue
                            config = data.get('config', {})
                            blocks: set[str] = set()

                            if feature_type in ('minecraft:ore', 'minecraft:scattered_ore'):
                                for target in config.get('targets', []):
                                    block_name = target.get('state', {}).get('Name', '')
                                    if block_name:
                                        blocks.add(block_name)

                            elif feature_type == 'minecraft:disk':
                                block_name = config.get('state', {}).get('Name', '')
                                if block_name:
                                    blocks.add(block_name)
                                for state in config.get('states', []):
                                    block_name = state.get('Name', '')
                                    if block_name:
                                        blocks.add(block_name)

                            if blocks:
                                configured_feature_to_blocks[configured_feature_id] = blocks
                        except Exception:
                            pass

    except zipfile.BadZipFile:
        pass
    except Exception as error:
        print(f'  !! {jar_path.name}: {error}')


def _build_feature_maps() -> tuple[dict, dict]:
    placed_to_configured_feature: dict[str, str]      = {}
    configured_feature_to_blocks: dict[str, set[str]] = {}
    jars = iter_jars(MODS_DIR, extra_jars=[VANILLA_JAR])
    print(f'\nScanning {len(jars)} jar(s) for worldgen feature JSONs...')
    for jar in jars:
        _scan_jar_for_features(jar, placed_to_configured_feature, configured_feature_to_blocks)
    print(f'placed_feature entries       : {len(placed_to_configured_feature)}')
    print(f'configured_feature w/ blocks : {len(configured_feature_to_blocks)}')
    return placed_to_configured_feature, configured_feature_to_blocks


def _resolve_biome_blocks(
    biome_features: dict,
    placed_to_configured_feature: dict,
    configured_feature_to_blocks: dict,
) -> set[str]:
    rows: set[str] = set()
    for biome_id, feature_ids in biome_features.items():
        for placed_feature_id in feature_ids:
            configured_feature_id = placed_to_configured_feature.get(placed_feature_id)
            if not configured_feature_id:
                continue
            blocks = configured_feature_to_blocks.get(configured_feature_id)
            if not blocks:
                continue
            for block_id in blocks:
                rows.add(f'{biome_id} | {block_id}')
    print(f'Biome->block pairs           : {len(rows)}')
    return rows


def main() -> None:
    biome_features                                     = _load_biome_features()
    placed_to_configured_feature, configured_feature_to_blocks = _build_feature_maps()
    rows = _resolve_biome_blocks(biome_features, placed_to_configured_feature, configured_feature_to_blocks)
    write_txt(LISTS, 'biome_blocks.txt', [(
        'BIOME BLOCKS  format: biome_id | block_id',
        sorted(rows),
    )])
    print('Done.')


main()
