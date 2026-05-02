"""
lib/jar_scanner.py
==================
Jar file iteration and data harvesting utilities.
"""

import zipfile
import re
from pathlib import Path

from lib.io_utils import read_json


def iter_jars(mods_dir: Path, extra_jars: list[Path] | None = None) -> list[Path]:
    """Return all .jar files in mods_dir (sorted), prepended with any extra_jars."""
    extras = [j for j in (extra_jars or []) if j.exists()]
    return extras + sorted(mods_dir.glob('*.jar'))


def scan_jars(mods_dir: Path, handlers: dict, extra_jars: list[Path] | None = None) -> None:
    """
    Iterate all jars and dispatch each JSON file to the appropriate handler.

    handlers is a dict mapping a path prefix (string) to a callable:
      {
        'recipes/':          collect_recipe,
        'loot_tables?/':     collect_loot,   # regex supported
        'worldgen/biome/':   collect_biome,
        ...
      }
    Keys that end with '/' are treated as plain prefixes.
    Keys containing regex metacharacters are treated as regex patterns.

    Each handler is called as: handler(namespace, relative_path, parsed_json)
    where relative_path is everything after data/<namespace>/

    Special keys:
      '__lang__'    : called with (namespace, lang_dict) for en_us.json files
      '__mod_meta__': called with (jar_path, zip_file) for each jar

    extra_jars: additional jars to scan before mod jars (e.g. vanilla client jar)
    """
    lang_handler     = handlers.get('__lang__')
    mod_meta_handler = handlers.get('__mod_meta__')

    # Pre-compile regex patterns
    compiled_handlers = []
    for prefix, handler in handlers.items():
        if prefix.startswith('__'):
            continue
        if any(c in prefix for c in r'.?*+[](){}\\'):
            compiled_handlers.append((re.compile(f'^{prefix}(.+)$'), handler))
        else:
            compiled_handlers.append((re.compile(f'^{re.escape(prefix)}(.+)$'), handler))

    jars = iter_jars(mods_dir, extra_jars)
    print(f'Found {len(jars)} jars ({len(extra_jars or [])} extra + mod jars)\n')

    for jar_path in jars:
        print(f'  Scanning {jar_path.name} ...')
        try:
            with zipfile.ZipFile(jar_path, 'r') as zip_file:

                if mod_meta_handler:
                    mod_meta_handler(jar_path, zip_file)

                for entry_name in zip_file.namelist():

                    # Lang files
                    if lang_handler and re.search(r'assets/[^/]+/lang/en_us\.json$', entry_name):
                        data = read_json(zip_file, entry_name)
                        if isinstance(data, dict):
                            namespace = entry_name.split('/')[1]
                            lang_handler(namespace, data)
                        continue

                    if not entry_name.startswith('data/') or not entry_name.endswith('.json'):
                        continue

                    parts = entry_name.split('/', 2)
                    if len(parts) < 3:
                        continue

                    namespace = parts[1]
                    relative  = parts[2]

                    data = read_json(zip_file, entry_name)
                    if not isinstance(data, dict):
                        continue

                    for pattern, handler in compiled_handlers:
                        match = pattern.match(relative)
                        if match:
                            handler(namespace, match.group(1), data)
                            break

        except zipfile.BadZipFile:
            print(f'    !! Skipping (not a valid zip): {jar_path.name}')
        except Exception as error:
            print(f'    !! Error in {jar_path.name}: {error}')
