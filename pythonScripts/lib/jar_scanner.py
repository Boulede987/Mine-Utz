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


def _compile_handlers(handlers: dict) -> list:
    compiled = []
    for prefix, handler in handlers.items():
        if prefix.startswith('__'):
            continue
        if any(c in prefix for c in r'.?*+[](){}\\'):
            compiled.append((re.compile(f'^{prefix}(.+)$'), handler))
        else:
            compiled.append((re.compile(f'^{re.escape(prefix)}(.+)$'), handler))
    return compiled


def _try_dispatch_lang(zip_file, entry_name: str, lang_handler) -> bool:
    if not re.search(r'assets/[^/]+/lang/en_us\.json$', entry_name):
        return False
    data = read_json(zip_file, entry_name)
    if isinstance(data, dict):
        namespace = entry_name.split('/')[1]
        lang_handler(namespace, data)
    return True


def _try_dispatch_data(zip_file, entry_name: str, compiled_handlers: list) -> None:
    if not entry_name.startswith('data/') or not entry_name.endswith('.json'):
        return
    parts = entry_name.split('/', 2)
    if len(parts) < 3:
        return
    namespace = parts[1]
    relative  = parts[2]
    data = read_json(zip_file, entry_name)
    if not isinstance(data, dict):
        return
    for pattern, handler in compiled_handlers:
        match = pattern.match(relative)
        if match:
            handler(namespace, match.group(1), data)
            break


def _scan_jar(jar_path: Path, compiled_handlers: list, lang_handler, mod_meta_handler) -> None:
    print(f'  Scanning {jar_path.name} ...')
    try:
        with zipfile.ZipFile(jar_path, 'r') as zip_file:
            if mod_meta_handler:
                mod_meta_handler(jar_path, zip_file)
            for entry_name in zip_file.namelist():
                if lang_handler and _try_dispatch_lang(zip_file, entry_name, lang_handler):
                    continue
                _try_dispatch_data(zip_file, entry_name, compiled_handlers)
    except zipfile.BadZipFile:
        print(f'    !! Skipping (not a valid zip): {jar_path.name}')
    except Exception as error:
        print(f'    !! Error in {jar_path.name}: {error}')


def scan_jars(mods_dir: Path, handlers: dict, extra_jars: list[Path] | None = None) -> None:
    """
    Iterate all jars and dispatch each JSON file to the appropriate handler.

    handlers maps a path prefix (string) to a callable:
      {
        'recipes/':          collect_recipe,
        'loot_tables?/':     collect_loot,   # regex supported
        'worldgen/biome/':   collect_biome,
      }
    Keys with regex metacharacters are treated as regex patterns; others as plain prefixes.
    Each handler is called as: handler(namespace, relative_path, parsed_json)
    where relative_path is everything after data/<namespace>/

    Special keys:
      '__lang__'    : called with (namespace, lang_dict) for en_us.json files
      '__mod_meta__': called with (jar_path, zip_file) for each jar
    """
    lang_handler      = handlers.get('__lang__')
    mod_meta_handler  = handlers.get('__mod_meta__')
    compiled_handlers = _compile_handlers(handlers)
    jars = iter_jars(mods_dir, extra_jars)
    print(f'Found {len(jars)} jars ({len(extra_jars or [])} extra + mod jars)\n')
    for jar_path in jars:
        _scan_jar(jar_path, compiled_handlers, lang_handler, mod_meta_handler)
