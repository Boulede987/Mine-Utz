"""
collectors/mod_meta.py
======================
Collects mod metadata from jar manifests.
Supports NeoForge/Forge (META-INF/mods.toml) and Fabric (fabric.mod.json).
"""

import re
import zipfile
from pathlib import Path

from lib.io_utils import read_json


def _parse_toml_value(raw: str, key: str) -> str:
    """Extract a single value from a TOML file using regex. Not a full parser."""
    match = re.search(rf'^\s*{key}\s*=\s*["\']?(.*?)["\']?\s*$', raw, re.MULTILINE)
    return match.group(1).strip('"\'') if match else ''


def collect_mod_meta(jar_path: Path, zip_file: zipfile.ZipFile, output_rows: list) -> None:
    """
    Extract mod metadata from a jar and append a pipe-separated row to output_rows.
    Format: mod_id | display_name | version | license | authors | url
    """
    entry_names = zip_file.namelist()

    if 'META-INF/mods.toml' in entry_names:
        try:
            raw = zip_file.read('META-INF/mods.toml').decode('utf-8', errors='ignore')
            mod_id       = _parse_toml_value(raw, 'modId')
            display_name = _parse_toml_value(raw, 'displayName')
            version      = _parse_toml_value(raw, 'version')
            license_name = _parse_toml_value(raw, 'license')
            authors      = _parse_toml_value(raw, 'authors')
            url          = _parse_toml_value(raw, 'displayURL')
            if mod_id:
                output_rows.append(
                    f"{mod_id} | {display_name} | v{version} | "
                    f"license:{license_name} | authors:{authors} | url:{url}"
                )
                return
        except Exception:
            pass

    if 'fabric.mod.json' in entry_names:
        try:
            data         = read_json(zip_file, 'fabric.mod.json')
            mod_id       = data.get('id', '')
            display_name = data.get('name', '')
            version      = data.get('version', '')
            license_raw  = data.get('license', '')
            license_name = ', '.join(license_raw) if isinstance(license_raw, list) else license_raw
            authors      = ', '.join(
                author if isinstance(author, str) else author.get('name', '')
                for author in data.get('authors', [])
            )
            contact = data.get('contact', {})
            url     = contact.get('sources', '') or contact.get('homepage', '')
            if mod_id:
                output_rows.append(
                    f"{mod_id} | {display_name} | v{version} | "
                    f"license:{license_name} | authors:{authors} | url:{url}"
                )
        except Exception:
            pass


def make_mod_meta_collector(output_rows: list) -> callable:
    """Returns a collect_mod_meta(jar_path, zip_file) function."""
    def _collect(jar_path: Path, zip_file: zipfile.ZipFile) -> None:
        collect_mod_meta(jar_path, zip_file, output_rows)
    return _collect
